package io.quarkus.arc.runtime.context;

import java.util.Map;

import org.eclipse.microprofile.context.ThreadContext;
import org.eclipse.microprofile.context.spi.ThreadContextController;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.spi.ThreadContextSnapshot;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.ManagedContext;

/**
 * Context propagation for Arc
 * Only handles Request context as that's currently the only one in Arc that needs propagation.
 */
public class ArcContextProvider implements ThreadContextProvider {

    protected static final ThreadContextController NOOP_CONTROLLER = new ThreadContextController() {
        @Override
        public void endContext() throws IllegalStateException {

        }
    };

    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> map) {
        ArcContainer arc = Arc.container();
        if (arc == null || !arc.isRunning()) {
            //return null as per docs to state that propagation of this context is not supported
            return null;
        }

        // capture the state, null indicates no active context while capturing snapshot
        InjectableContext.ContextState state = isContextActiveOnThisThread(arc) ? arc.requestContext().getState() : null;
        return new ThreadContextSnapshot() {
            @Override
            public ThreadContextController begin() {
                // can be called later on, we should retrieve the container again
                ArcContainer arcContainer = Arc.container();
                if (arcContainer == null || !arcContainer.isRunning()) {
                    //this happens on shutdown, if we blow up here it can break shutdown, and stop
                    //resources from being cleaned up, causing tests to fail
                    return NOOP_CONTROLLER;
                }
                ThreadContextController controller;
                ManagedContext requestContext = arcContainer.requestContext();
                // this is executed on another thread, context can but doesn't need to be active here
                if (ArcContextProvider.this.isContextActiveOnThisThread(arcContainer)) {
                    // context active, store current state, feed it new one and restore state afterwards
                    InjectableContext.ContextState stateToRestore = requestContext.getState();
                    requestContext.deactivate();
                    if (state != null) {
                        // only activate if previous thread had it active
                        requestContext.activate(state);
                    }
                    controller = new ThreadContextController() {
                        @Override
                        public void endContext() throws IllegalStateException {
                            // clean up, reactivate context with previous values
                            if (state != null) {
                                // only deactivate if previous thread had it active
                                requestContext.deactivate();
                            }
                            requestContext.activate(stateToRestore);
                        }
                    };
                } else {
                    // context not active, activate and pass it new instance, deactivate afterwards
                    if (state != null) {
                        // only activate if previous thread had it active
                        requestContext.activate(state);

                    }
                    controller = new ThreadContextController() {
                        @Override
                        public void endContext() throws IllegalStateException {
                            if (state != null) {
                                // only deactivate if previous thread had it active
                                requestContext.deactivate();
                            }
                        }
                    };
                }
                return controller;
            }
        };
    }

    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> map) {
        // note that by cleared we mean that we still activate context if need be, just leave the contents blank
        ArcContainer arc = Arc.container();
        if (arc == null || !arc.isRunning()) {
            //return null as per docs to state that propagation of this context is not supported
            return null;
        }

        return new ThreadContextSnapshot() {
            @Override
            public ThreadContextController begin() {
                // c
                // an be called later on, we should retrieve the container again
                ArcContainer arcContainer = Arc.container();
                if (arcContainer == null || !arcContainer.isRunning()) {
                    return NOOP_CONTROLLER;
                }
                ThreadContextController controller;
                ManagedContext requestContext = arcContainer.requestContext();
                // this is executed on another thread, context can but doesn't need to be active here
                if (ArcContextProvider.this.isContextActiveOnThisThread(arcContainer)) {
                    // context active, store current state, start blank context anew and restore state afterwards
                    InjectableContext.ContextState stateToRestore = requestContext.getState();
                    requestContext.deactivate();
                    requestContext.activate();
                    controller = () -> {
                        // clean up, reactivate context with previous values
                        requestContext.deactivate();
                        requestContext.activate(stateToRestore);
                    };
                } else {
                    // context not active, activate blank one, deactivate afterwards
                    requestContext.activate();
                    controller = new ThreadContextController() {
                        @Override
                        public void endContext() throws IllegalStateException {
                            requestContext.deactivate();
                        }
                    };
                }
                return controller;
            }
        };
    }

    @Override
    public String getThreadContextType() {
        return ThreadContext.CDI;
    }

    private boolean isContextActiveOnThisThread(ArcContainer arc) {
        return arc.requestContext().isActive();
    }
}
