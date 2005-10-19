/*
 *
 *   Copyright 2005 The Apache Software Foundation.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.commons.scxml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.scxml.model.History;
import org.apache.commons.scxml.model.ModelException;
import org.apache.commons.scxml.model.SCXML;
import org.apache.commons.scxml.model.State;
import org.apache.commons.scxml.model.TransitionTarget;
import org.apache.commons.scxml.semantics.SCXMLSemanticsImpl;

/**
 * <p>The SCXML &quot;engine&quot; that executes SCXML documents. The
 * particular semantics used by this engine for executing the SCXML are
 * encapsulated in the SCXMLSemantics implementation that it uses.</p>
 *
 * <p>The default implementation is
 * <code>org.apache.commons.scxml.semantics.SCXMLSemanticsImpl</code></p>
 *
 * @see SCXMLSemantics
 */
public class SCXMLExecutor {

    /**
     * The Logger for the SCXMLExecutor.
     */
    private static Log log = LogFactory.getLog(SCXMLExecutor.class);

    /**
     * The stateMachine being executed.
     */
    private SCXML stateMachine;

    /**
     * The evaluator for expressions.
     */
    private Evaluator evaluator;

    /**
     * The current status of the stateMachine.
     */
    private Status currentStatus;

    /**
     * The event dispatcher to interface with external documents etc.
     */
    private EventDispatcher eventdispatcher;

    /**
     * The environment specific error reporter.
     */
    private ErrorReporter errorReporter = null;

    /**
     * Run-to-completion.
     */
    private boolean superStep = true;

    /**
     *  Interpretation semantics.
     */
    private SCXMLSemantics semantics;

    /**
     * The worker method.
     * Re-evaluates current status whenever any events are triggered.
     *
     * @param evts
     *            an array of external events which triggered during the last
     *            time quantum
     * @throws ModelException in case there is a fatal SCXML object
     *            model problem.
     */
    public void triggerEvents(final TriggerEvent[] evts)
            throws ModelException {
        ArrayList evs = new ArrayList(Arrays.asList(evts));
        do {
            // CreateStep
            Step step = new Step(evs, currentStatus);
            // EnumerateReachableTransitions
            semantics.enumerateReachableTransitions(stateMachine, step,
                    errorReporter);
            // FilterTransitionSet
            semantics.filterTransitionsSet(step, evaluator, errorReporter);
            // FollowTransitions
            semantics.followTransitions(step, errorReporter);
            // UpdateHistoryStates
            semantics.updateHistoryStates(step, errorReporter);
            // ExecuteActions
            semantics.executeActions(step, stateMachine, evaluator,
                    eventdispatcher, errorReporter);
            // AssignCurrentStatus
            updateStatus(step);
            // ***Cleanup external events if superStep
            if (superStep) {
                evs.clear();
            }
        } while(superStep && currentStatus.getEvents().size() > 0);
        logState();
    }

    /**
     * Constructor.
     *
     * @param expEvaluator The expression evaluator
     * @param evtDisp The event dispatcher
     * @param errRep The error reporter
     */
    public SCXMLExecutor(final Evaluator expEvaluator,
            final EventDispatcher evtDisp, final ErrorReporter errRep) {
        this(expEvaluator, evtDisp, errRep, null);    }

    /**
     * Convenience constructor.
     */
    public SCXMLExecutor() {
        this(null, null, null, null);
    }
    
    /**
     * Constructor.
     *
     * @param expEvaluator The expression evaluator
     * @param evtDisp The event dispatcher
     * @param errRep The error reporter
     */
    public SCXMLExecutor(final Evaluator expEvaluator,
            final EventDispatcher evtDisp, final ErrorReporter errRep,
            final SCXMLSemanticsImpl semantics) {
        this.evaluator = expEvaluator;
        this.eventdispatcher = evtDisp;
        this.errorReporter = errRep;
        this.currentStatus = null;
        this.stateMachine = null;
        if (semantics == null) {
            // Use default semantics, if none provided
            this.semantics = new SCXMLSemanticsImpl();
        } else {
            this.semantics = semantics;
        }
        this.currentStatus = null;
        this.stateMachine = null;
    }    

    /**
     * Clear all state and begin from &quot;initialstate&quot; indicated
     * on root SCXML element.
     *
     * @throws ModelException in case there is a fatal SCXML object
     *         model problem.
     */
    public void reset() throws ModelException {
        // Reset all variable contexts
        stateMachine.getRootContext().reset();
        // all states and parallels, only states have var. contexts
        for (Iterator i = stateMachine.getTargets().values().iterator();
                i.hasNext();) {
            TransitionTarget tt = (TransitionTarget) i.next();
            if (tt instanceof State) {
                ((State) tt).getContext().reset();
            } else if (tt instanceof History) {
                ((History) tt).reset();
            }
        }
        // CreateEmptyStatus
        currentStatus = new Status();
        Step step = new Step(null, currentStatus);
        // DetermineInitialStates
        semantics.determineInitialStates(stateMachine,
                step.getAfterStatus().getStates(),
                step.getEntryList(), errorReporter);
        // ExecuteActions
        semantics.executeActions(step, stateMachine, evaluator, 
                eventdispatcher, errorReporter);
        // AssignCurrentStatus
        updateStatus(step);
        // Execute Immediate Transitions
        if (superStep && currentStatus.getEvents().size() > 0) {
            this.triggerEvents(new TriggerEvent[0]);
        } else {
            logState();
        }
    }

    /**
     * Get the current status.
     *
     * @return The current Status
     */
    public Status getCurrentStatus() {
        return currentStatus;
    }

    /**
     * Get the expression evaluator.
     *
     * @return Returns the evaluator.
     */
    public Evaluator getEvaluator() {
        return evaluator;
    }

    /**
     * @param evaluator The evaluator to set.
     */
    public void setEvaluator(final Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * Get the state machine that is being executed.
     *
     * @return Returns the stateMachine.
     */
    public SCXML getStateMachine() {
        return stateMachine;
    }

    /**
     * Set the state machine to be executed.
     *
     * @param stateMachine The stateMachine to set.
     * @throws ModelException in case there is a fatal SCXML object
     *  model problem.
     */
    public void setStateMachine(final SCXML stateMachine)
            throws ModelException {
        // NormalizeStateMachine
        SCXML sm = semantics.normalizeStateMachine(stateMachine,
                errorReporter);
        // StoreStateMachine
        this.stateMachine = sm;
        // reset
        this.reset();
    }

    /**
     * Get the environment specific error reporter.
     *
     * @return Returns the errorReporter.
     */
    public ErrorReporter getErrorReporter() {
        return errorReporter;
    }

    /**
     * Set the environment specific error reporter.
     *
     * @param errorReporter The errorReporter to set.
     */
    public void setErrorReporter(final ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }

    /**
     * Get the event dispatcher.
     *
     * @return Returns the eventdispatcher.
     */
    public EventDispatcher getEventdispatcher() {
        return eventdispatcher;
    }

    /**
     * Set the event dispatcher.
     *
     * @param eventdispatcher The eventdispatcher to set.
     */
    public void setEventdispatcher(final EventDispatcher eventdispatcher) {
        this.eventdispatcher = eventdispatcher;
    }

    /**
     * Use &quot;super-step&quot;, default is <code>true</code>
     * (that is, run-to-completion is default).
     *
     * @return Returns the superStep property.
     * @see #setSuperStep(boolean)
     */
    public boolean isSuperStep() {
        return superStep;
    }

    /**
     * Set the super step.
     *
     * @param superStep
     * if true, the internal derived events are also processed
     *    (run-to-completion);
     * if false, the internal derived events are stored in the
     * CurrentStatus property and processed within the next
     * triggerEvents() invocation, also the immediate (empty event) transitions
     * are deferred until the next step
      */
    public void setSuperStep(final boolean superStep) {
        this.superStep = superStep;
    }

    /**
     * Log the current set of active states.
     */
    private void logState() {
        if (log.isInfoEnabled()) {
            Iterator si = currentStatus.getStates().iterator();
            StringBuffer sb = new StringBuffer("Current States: [");
            while (si.hasNext()) {
                State s = (State) si.next();
                sb.append(s.getId());
                if (si.hasNext()) {
                    sb.append(", ");
                }
            }
            sb.append(']');
            log.info(sb.toString());
        }
    }

    /**
     * @param step The most recent Step
     */
    private void updateStatus(final Step step) {
        this.currentStatus = step.getAfterStatus();
        stateMachine.getRootContext().setLocal("_ALL_STATES",
            SCXMLHelper.getAncestorClosure(currentStatus.getStates(), null));
    }
}
