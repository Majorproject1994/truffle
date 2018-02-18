/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.instrumentation;

import java.io.PrintStream;
import java.util.concurrent.locks.Lock;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.InstrumentableFactory.WrapperNode;
import com.oracle.truffle.api.instrumentation.InstrumentationHandler.InstrumentClientInstrumenter;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;

/**
 * <p>
 * Represents an event sink for instrumentation events that is embedded in the AST using wrappers if
 * needed. Instances of this class are provided by
 * {@link InstrumentableFactory#createWrapper(Node, ProbeNode)} to notify the instrumentation API
 * about execution events.
 * </p>
 *
 * The recommended use of this node for implementing {@link WrapperNode wrapper nodes} looks as
 * follows:
 *
 * <pre>
 * &#064;Override
 * public Object execute(VirtualFrame frame) {
 *     Object returnValue;
 *     for (;;) {
 *         boolean wasOnReturnExecuted = false;
 *         try {
 *             probeNode.onEnter(frame);
 *             returnValue = delegateNode.executeGeneric(frame);
 *             wasOnReturnExecuted = true;
 *             probeNode.onReturnValue(frame, returnValue);
 *             break;
 *         } catch (Throwable t) {
 *             Object result = probeNode.onReturnExceptionalOrUnwind(frame, t, wasOnReturnExecuted);
 *             if (result == ProbeNode.UNWIND_ACTION_REENTER) {
 *                 continue;
 *             } else if (result != null) {
 *                 returnValue = result;
 *                 break;
 *             }
 *             throw t;
 *         }
 *     }
 *     return returnValue;
 * }
 * </pre>
 *
 * @since 0.12
 */
public final class ProbeNode extends Node {

    /**
     * A constant that performs reenter of the current node when returned from
     * {@link ExecutionEventListener#onUnwind(EventContext, VirtualFrame, Object)} or
     * {@link ExecutionEventNode#onUnwind(VirtualFrame, Object)}.
     * 
     * @since 0.31
     */
    public static final Object UNWIND_ACTION_REENTER = new Object();

    // returned from chain nodes whose bindings ignore the unwind
    private static final Object UNWIND_ACTION_IGNORED = new Object();

    private final InstrumentationHandler handler;
    @CompilationFinal private volatile EventContext context;

    @Child private volatile ProbeNode.EventChainNode chain;

    /*
     * We cache to ensure that the instrumented tags and source sections are always compilation
     * final for listeners and factories.
     */
    @CompilationFinal private volatile Assumption version;

    @CompilationFinal private volatile byte seen = 0;
    private final BranchProfile unwindHasNext = BranchProfile.create();

    /** Instantiated by the instrumentation framework. */
    ProbeNode(InstrumentationHandler handler, SourceSection sourceSection) {
        this.handler = handler;
        this.context = new EventContext(this, sourceSection);
    }

    /**
     * Should get invoked before the node is invoked.
     *
     * @param frame the current frame of the execution.
     * @since 0.12
     */
    public void onEnter(VirtualFrame frame) {
        EventChainNode localChain = lazyUpdate(frame);
        if (localChain != null) {
            localChain.onEnter(context, frame);
        }
    }

    /**
     * Should get invoked after the node is invoked successfully.
     *
     * @param result the result value of the operation
     * @param frame the current frame of the execution.
     * @since 0.12
     */
    public void onReturnValue(VirtualFrame frame, Object result) {
        EventChainNode localChain = lazyUpdate(frame);
        if (localChain != null) {
            localChain.onReturnValue(context, frame, result);
        }
    }

    /**
     * Should get invoked if the node did not complete successfully.
     *
     * @param exception the exception that occurred during the execution
     * @param frame the current frame of the execution.
     * @since 0.12
     * @deprecated Use {@link #onReturnExceptionalOrUnwind(VirtualFrame, Throwable, boolean)}
     *             instead and adjust the wrapper node implementation accordingly.
     */
    @Deprecated
    public void onReturnExceptional(VirtualFrame frame, Throwable exception) {
        if (exception instanceof ThreadDeath) {
            throw (ThreadDeath) exception;
        }
        EventChainNode localChain = lazyUpdate(frame);
        if (localChain != null) {
            localChain.onReturnExceptional(context, frame, exception);
        }
    }

    /**
     * Creates a shallow copy of this node.
     *
     * @return the new copy
     * @since 0.31
     */
    @Override
    public Node copy() {
        ProbeNode pn = (ProbeNode) super.copy();
        pn.context = new EventContext(pn, context.getInstrumentedSourceSection());
        return pn;
    }

    /**
     * Should get invoked if the node did not complete successfully and handle a possible unwind.
     * When a non-<code>null</code> value is returned, a change of the execution path was requested
     * by an {@link EventContext#createUnwind(Object) unwind}.
     *
     * @param exception the exception that occurred during the execution
     * @param frame the current frame of the execution.
     * @param isReturnCalled <code>true</code> when {@link #onReturnValue(VirtualFrame, Object)} was
     *            called already for this node's execution, <code>false</code> otherwise. This helps
     *            to assure correct pairing of enter/return notifications.
     * @return <code>null</code> to proceed to throw of the exception,
     *         {@link #UNWIND_ACTION_REENTER} to reenter the current node, or an interop value to
     *         return that value early from the current node (void nodes just return, ignoring the
     *         return value).
     * @since 0.31
     */
    public Object onReturnExceptionalOrUnwind(VirtualFrame frame, Throwable exception, boolean isReturnCalled) {
        UnwindException unwind = null;
        if (exception instanceof UnwindException) {
            if (!isSeenUnwind()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                setSeenUnwind();
            }
            unwind = (UnwindException) exception;
        } else if (exception instanceof ThreadDeath) {
            throw (ThreadDeath) exception;
        }
        EventChainNode localChain = lazyUpdate(frame);
        if (localChain != null) {
            if (!isReturnCalled) {
                try {
                    localChain.onReturnExceptional(context, frame, exception);
                } catch (UnwindException ex) {
                    if (!isSeenUnwind()) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        setSeenUnwind();
                    }
                    if (unwind != null && unwind != ex) {
                        unwindHasNext.enter();
                        unwind.addNext(ex);
                    } else {
                        unwind = ex;
                    }
                }
            }
            if (unwind != null) { // seenUnwind must be true here
                Object ret = localChain.onUnwind(context, frame, unwind);
                if (ret == UNWIND_ACTION_REENTER) {
                    if (!isSeenReenter()) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        setSeenReenter();
                    }
                    return UNWIND_ACTION_REENTER;
                } else if (ret != null && ret != UNWIND_ACTION_IGNORED) {
                    if (!isSeenReturn()) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        setSeenReturn();
                    }
                    return ret;
                }
                throw unwind;
            }
        }
        return null;
    }

    private boolean isSeenUnwind() {
        return (seen & 0b1) != 0;
    }

    private void setSeenUnwind() {
        CompilerAsserts.neverPartOfCompilation();
        seen = (byte) (seen | 0b1);
    }

    private boolean isSeenReenter() {
        return (seen & 0b10) != 0;
    }

    private void setSeenReenter() {
        CompilerAsserts.neverPartOfCompilation();
        seen = (byte) (seen | 0b10);
    }

    private boolean isSeenReturn() {
        return (seen & 0b100) != 0;
    }

    private void setSeenReturn() {
        CompilerAsserts.neverPartOfCompilation();
        seen = (byte) (seen | 0b100);
    }

    EventContext getContext() {
        return context;
    }

    WrapperNode findWrapper() throws AssertionError {
        Node parent = getParent();
        if (!(parent instanceof WrapperNode)) {
            if (parent == null) {
                throw new AssertionError("Probe node disconnected from AST.");
            } else {
                throw new AssertionError("ProbeNodes must have a parent Node that implements NodeWrapper.");
            }
        }
        return (WrapperNode) parent;
    }

    synchronized void invalidate() {
        Assumption localVersion = this.version;
        if (localVersion != null) {
            localVersion.invalidate();
        }
    }

    ExecutionEventNode findEventNode(final ExecutionEventNodeFactory factory) {
        if (version != null && version.isValid() && chain != null) {
            return findEventNodeInChain(factory);
        }
        return null;
    }

    private ExecutionEventNode findEventNodeInChain(ExecutionEventNodeFactory factory) {
        EventChainNode currentChain = this.chain;
        while (currentChain != null) {
            if (currentChain.binding.getElement() == factory) {
                return ((EventProviderChainNode) currentChain).eventNode;
            }
            currentChain = currentChain.next;
        }
        return null;
    }

    private EventChainNode lazyUpdate(VirtualFrame frame) {
        Assumption localVersion = this.version;
        if (localVersion == null || !localVersion.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // Ok to pass in the virtual frame as its instances are always materialized
            return lazyUpdatedImpl(frame);
        }
        return this.chain;
    }

    private EventChainNode lazyUpdatedImpl(VirtualFrame frame) {
        EventChainNode oldChain;
        EventChainNode nextChain;
        Lock lock = getLock();
        lock.lock();
        try {
            Assumption localVersion = this.version;
            if (localVersion != null && localVersion.isValid()) {
                return this.chain;
            }
            nextChain = handler.createBindings(ProbeNode.this);
            if (nextChain == null) {
                // chain is null -> remove wrapper;
                // Note: never set child nodes to null, can cause races
                InstrumentationHandler.removeWrapper(ProbeNode.this);
                return null;
            }

            oldChain = this.chain;
            this.chain = insert(nextChain);
            this.version = Truffle.getRuntime().createAssumption("Instruments unchanged");
        } finally {
            lock.unlock();
        }

        if (oldChain != null) {
            oldChain.onDispose(context, frame);
        }

        return nextChain;
    }

    ExecutionEventNode lookupExecutionEventNode(EventBinding<?> binding) {
        if (binding.isDisposed()) {
            return null;
        }
        EventChainNode chainNode = this.chain;
        while (chainNode != null) {
            if (chainNode.binding == binding) {
                if (chainNode instanceof EventProviderChainNode) {
                    return ((EventProviderChainNode) chainNode).eventNode;
                }
            }
            chainNode = chainNode.next;
        }
        return null;
    }

    ProbeNode.EventChainNode createEventChainCallback(EventBinding.Source<?> binding) {
        ProbeNode.EventChainNode next;
        Object element = binding.getElement();
        if (element instanceof ExecutionEventListener) {
            next = new EventFilterChainNode(binding, (ExecutionEventListener) element);
        } else {
            assert element instanceof ExecutionEventNodeFactory;
            ExecutionEventNode eventNode = createEventNode(binding, element);
            if (eventNode == null) {
                // error occurred creating the event node
                return null;
            }
            next = new EventProviderChainNode(binding, eventNode);
        }
        return next;
    }

    private ExecutionEventNode createEventNode(EventBinding.Source<?> binding, Object element) {
        ExecutionEventNode eventNode;
        try {
            eventNode = ((ExecutionEventNodeFactory) element).create(context);
            if (eventNode.getParent() != null) {
                throw new IllegalStateException(String.format("Returned ExecutionEventNode %s was already adopted by another AST.", eventNode));
            }
        } catch (Throwable t) {
            if (binding.isLanguageBinding()) {
                /* Language bindings can just throw exceptions directly into the AST. */
                throw t;
            } else {
                /*
                 * Client Instruments are not allowed to disrupt program execution by throwing
                 * exceptions into the AST.
                 */
                exceptionEventForClientInstrument(binding, "ProbeNodeFactory.create", t);
                return null;
            }
        }
        return eventNode;
    }

    /**
     * Handles exceptions from non-language instrumentation code that must not be allowed to alter
     * guest language execution semantics. Normal response is to log and continue.
     */
    @TruffleBoundary
    static void exceptionEventForClientInstrument(EventBinding.Source<?> b, String eventName, Throwable t) {
        assert !b.isLanguageBinding();
        if (t instanceof ThreadDeath) {
            // Terminates guest language execution immediately
            throw (ThreadDeath) t;
        }
        // Exception is a failure in (non-language) instrumentation code; log and continue
        InstrumentClientInstrumenter instrumenter = (InstrumentClientInstrumenter) b.getInstrumenter();
        Class<?> instrumentClass = instrumenter.getInstrumentClass();

        String message = String.format("Event %s failed for instrument class %s and listener/factory %s.", //
                        eventName, instrumentClass.getName(), b.getElement());

        Exception exception = new Exception(message, t);
        PrintStream stream = new PrintStream(instrumenter.getEnv().err());
        exception.printStackTrace(stream);
    }

    /** @since 0.12 */
    @Override
    public NodeCost getCost() {
        return NodeCost.NONE;
    }

    private static boolean checkInteropType(Object value, EventBinding.Source<?> binding) {
        if (value != null && value != UNWIND_ACTION_REENTER && !InstrumentationHandler.ACCESSOR.isTruffleObject(value)) {
            Class<?> clazz = value.getClass();
            if (!(clazz == Byte.class ||
                            clazz == Short.class ||
                            clazz == Integer.class ||
                            clazz == Long.class ||
                            clazz == Float.class ||
                            clazz == Double.class ||
                            clazz == Character.class ||
                            clazz == Boolean.class ||
                            clazz == String.class)) {
                CompilerDirectives.transferToInterpreter();
                ClassCastException ccex = new ClassCastException(clazz.getName() + " isn't allowed Truffle interop type!");
                if (binding.isLanguageBinding()) {
                    throw ccex;
                } else {
                    exceptionEventForClientInstrument(binding, "onUnwind", ccex);
                    return false;
                }
            }
        }
        return true;
    }

    private static Object mergePostUnwindReturns(Object r1, Object r2) {
        // Prefer unwind
        if (r1 == null || r2 == null) {
            return null;
        }
        if (r1 == UNWIND_ACTION_IGNORED) {
            return r2;
        }
        if (r2 == UNWIND_ACTION_IGNORED) {
            return r1;
        }
        // Prefer reenter over return
        if (r1 == UNWIND_ACTION_REENTER || r2 == UNWIND_ACTION_REENTER) {
            return UNWIND_ACTION_REENTER;
        }
        return r1; // The first one wins
    }

    abstract static class EventChainNode extends Node {

        @Child private ProbeNode.EventChainNode next;
        private final EventBinding.Source<?> binding;
        private final BranchProfile unwindHasNext = BranchProfile.create();
        @CompilationFinal private byte seen = 0;

        EventChainNode(EventBinding.Source<?> binding) {
            this.binding = binding;
        }

        final void setNext(ProbeNode.EventChainNode next) {
            this.next = insert(next);
        }

        EventBinding.Source<?> getBinding() {
            return binding;
        }

        ProbeNode.EventChainNode getNext() {
            return next;
        }

        @Override
        public final NodeCost getCost() {
            return NodeCost.NONE;
        }

        private boolean isSeenException() {
            return (seen & 0b1) != 0;
        }

        private void setSeenException() {
            CompilerAsserts.neverPartOfCompilation();
            seen = (byte) (seen | 0b1);
        }

        private boolean isSeenUnwind() {
            return (seen & 0b10) != 0;
        }

        private void setSeenUnwind() {
            CompilerAsserts.neverPartOfCompilation();
            seen = (byte) (seen | 0b10);
        }

        final void onDispose(EventContext context, VirtualFrame frame) {
            try {
                innerOnDispose(context, frame);
            } catch (Throwable t) {
                if (!isSeenException()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenException();
                }
                if (binding.isLanguageBinding()) {
                    throw t;
                } else {
                    exceptionEventForClientInstrument(binding, "onEnter", t);
                }
            }
            if (next != null) {
                next.onDispose(context, frame);
            }
        }

        protected abstract void innerOnDispose(EventContext context, VirtualFrame frame);

        final void onEnter(EventContext context, VirtualFrame frame) {
            UnwindException unwind = null;
            try {
                innerOnEnter(context, frame);
            } catch (UnwindException ex) {
                if (!isSeenUnwind()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenUnwind();
                }
                ex.thrownFromBinding(binding);
                unwind = ex;
            } catch (Throwable t) {
                if (!isSeenException()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenException();
                }
                if (binding.isLanguageBinding()) {
                    throw t;
                } else {
                    CompilerDirectives.transferToInterpreter();
                    exceptionEventForClientInstrument(binding, "onEnter", t);
                }
            }
            if (next != null) {
                try {
                    next.onEnter(context, frame);
                } catch (UnwindException ex) {
                    if (!isSeenUnwind()) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        setSeenUnwind();
                    }
                    if (unwind != null && unwind != ex) {
                        unwindHasNext.enter();
                        unwind.addNext(ex);
                    } else {
                        unwind = ex;
                    }
                }
            }
            if (unwind != null) {
                throw unwind;
            }
        }

        protected abstract void innerOnEnter(EventContext context, VirtualFrame frame);

        final void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
            UnwindException unwind = null;
            if (next != null) {
                try {
                    next.onReturnValue(context, frame, result);
                } catch (UnwindException ex) {
                    if (!isSeenUnwind()) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        setSeenUnwind();
                    }
                    unwind = ex;
                }
            }
            try {
                innerOnReturnValue(context, frame, result);
            } catch (UnwindException ex) {
                if (!isSeenUnwind()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenUnwind();
                }
                ex.thrownFromBinding(binding);
                if (unwind != null && unwind != ex) {
                    unwindHasNext.enter();
                    unwind.addNext(ex);
                } else {
                    unwind = ex;
                }
            } catch (Throwable t) {
                if (!isSeenException()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenException();
                }
                if (binding.isLanguageBinding()) {
                    throw t;
                } else {
                    CompilerDirectives.transferToInterpreter();
                    exceptionEventForClientInstrument(binding, "onReturnValue", t);
                }
            }
            if (unwind != null) {
                throw unwind;
            }
        }

        protected abstract void innerOnReturnValue(EventContext context, VirtualFrame frame, Object result);

        final void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            UnwindException unwind = null;
            if (exception instanceof UnwindException) {
                if (!isSeenUnwind()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenUnwind();
                }
                unwind = (UnwindException) exception;
                assert unwind.getBinding() != null;
            }
            if (next != null) {
                try {
                    next.onReturnExceptional(context, frame, exception);
                } catch (UnwindException ex) {
                    if (!isSeenUnwind()) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        setSeenUnwind();
                    }
                    if (unwind != null && unwind != ex) {
                        unwindHasNext.enter();
                        unwind.addNext(ex);
                    } else {
                        unwind = ex;
                    }
                }
            }
            try {
                innerOnReturnExceptional(context, frame, exception);
            } catch (UnwindException ex) {
                if (!isSeenUnwind()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenUnwind();
                }
                ex.thrownFromBinding(binding);
                if (unwind != null && unwind != ex) {
                    unwindHasNext.enter();
                    unwind.addNext(ex);
                } else {
                    unwind = ex;
                }
            } catch (Throwable t) {
                if (!isSeenException()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setSeenException();
                }
                if (binding.isLanguageBinding()) {
                    exception.addSuppressed(t);
                } else {
                    CompilerDirectives.transferToInterpreter();
                    exceptionEventForClientInstrument(binding, "onReturnExceptional", t);
                }
            }
            if (unwind != null) {
                throw unwind;
            }
        }

        protected abstract void innerOnReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception);

        private boolean containsBinding(UnwindException unwind) {
            if (unwind.getBinding() == binding) {
                return true;
            } else {
                UnwindException nextUnwind = unwind.getNext();
                if (nextUnwind != null) {
                    unwindHasNext.enter();
                    return containsBindingBoundary(nextUnwind);
                } else {
                    return false;
                }
            }
        }

        @TruffleBoundary
        private boolean containsBindingBoundary(UnwindException unwind) {
            return containsBinding(unwind);
        }

        private Object getInfo(UnwindException unwind) {
            if (unwind.getBinding() == binding) {
                return unwind.getInfo();
            } else {
                UnwindException nextUnwind = unwind.getNext();
                if (nextUnwind != null) {
                    unwindHasNext.enter();
                    return getInfoBoundary(nextUnwind);
                } else {
                    return false;
                }
            }
        }

        @TruffleBoundary
        private Object getInfoBoundary(UnwindException unwind) {
            return getInfo(unwind);
        }

        private void reset(UnwindException unwind) {
            if (unwind.getBinding() == binding) {
                unwind.resetThread();
            } else {
                UnwindException nextUnwind = unwind.getNext();
                if (nextUnwind != null) {
                    unwindHasNext.enter();
                    unwind.resetBoundary(binding);
                }
            }
        }

        final Object onUnwind(EventContext context, VirtualFrame frame, UnwindException unwind) {
            Object ret = null;
            if (containsBinding(unwind)) {
                try {
                    ret = innerOnUnwind(context, frame, getInfo(unwind));
                } catch (Throwable t) {
                    if (!isSeenException()) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        setSeenException();
                    }
                    if (binding.isLanguageBinding()) {
                        throw t;
                    } else {
                        CompilerDirectives.transferToInterpreter();
                        exceptionEventForClientInstrument(binding, "onUnwind", t);
                    }
                }
                if (ret != null) {
                    assert checkInteropType(ret, binding);
                    reset(unwind);
                }
            } else {
                ret = UNWIND_ACTION_IGNORED;
            }
            if (next != null) {
                Object nextRet = next.onUnwind(context, frame, unwind);
                ret = mergePostUnwindReturns(ret, nextRet);
            }
            return ret;
        }

        protected abstract Object innerOnUnwind(EventContext context, VirtualFrame frame, Object info);
    }

    private static class EventFilterChainNode extends ProbeNode.EventChainNode {

        private final ExecutionEventListener listener;

        EventFilterChainNode(EventBinding.Source<?> binding, ExecutionEventListener listener) {
            super(binding);
            this.listener = listener;
        }

        @Override
        protected void innerOnEnter(EventContext context, VirtualFrame frame) {
            listener.onEnter(context, frame);
        }

        @Override
        protected void innerOnReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            listener.onReturnExceptional(context, frame, exception);
        }

        @Override
        protected void innerOnReturnValue(EventContext context, VirtualFrame frame, Object result) {
            listener.onReturnValue(context, frame, result);
        }

        @Override
        protected Object innerOnUnwind(EventContext context, VirtualFrame frame, Object info) {
            return listener.onUnwind(context, frame, info);
        }

        @Override
        protected void innerOnDispose(EventContext context, VirtualFrame frame) {
        }

    }

    private static class EventProviderChainNode extends ProbeNode.EventChainNode {

        @Child private ExecutionEventNode eventNode;

        EventProviderChainNode(EventBinding.Source<?> binding, ExecutionEventNode eventNode) {
            super(binding);
            this.eventNode = eventNode;
        }

        @Override
        protected void innerOnEnter(EventContext context, VirtualFrame frame) {
            eventNode.onEnter(frame);
        }

        @Override
        protected void innerOnReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            eventNode.onReturnExceptional(frame, exception);
        }

        @Override
        protected void innerOnReturnValue(EventContext context, VirtualFrame frame, Object result) {
            eventNode.onReturnValue(frame, result);
        }

        @Override
        protected Object innerOnUnwind(EventContext context, VirtualFrame frame, Object info) {
            return eventNode.onUnwind(frame, info);
        }

        @Override
        protected void innerOnDispose(EventContext context, VirtualFrame frame) {
            eventNode.onDispose(frame);
        }

    }
}
