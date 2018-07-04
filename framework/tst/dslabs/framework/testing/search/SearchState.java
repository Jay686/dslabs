/*
 * Copyright (c) 2018 Ellis Michael (emichael@cs.washington.edu)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dslabs.framework.testing.search;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import dslabs.framework.Address;
import dslabs.framework.Message;
import dslabs.framework.Node;
import dslabs.framework.Timer;
import dslabs.framework.testing.AbstractState;
import dslabs.framework.testing.ClientWorker;
import dslabs.framework.testing.MessageEnvelope;
import dslabs.framework.testing.StateGenerator;
import dslabs.framework.testing.TimerEnvelope;
import dslabs.framework.testing.utils.Cloning;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.tuple.Pair;

@Log
@EqualsAndHashCode(callSuper = true)
public final class SearchState extends AbstractState
        implements Serializable, Cloneable {
    private final Set<MessageEnvelope> network;
    private final Map<Address, TimerQueue> timers;

    @Getter private final transient SearchState previous;
    @Getter private final transient Event previousEvent;
    @Getter private final transient int depth;

    // TODO: only return iterable for these in getter?
    @Getter private final transient Set<MessageEnvelope> newMessages;
    @Getter private final transient Set<TimerEnvelope> newTimers;

    public SearchState(Set<Address> servers, Set<Address> clientWorkers,
                       StateGenerator stateGenerator) {
        super(servers, clientWorkers, Collections.emptySet(), stateGenerator);

        this.network = new HashSet<>();
        this.timers = new HashMap<>();
        this.previous = null;
        this.previousEvent = null;
        this.depth = 0;
        this.newMessages = new HashSet<>();
        this.newTimers = new HashSet<>();
    }

    public SearchState(StateGenerator stateGenerator) {
        this(Collections.emptySet(), Collections.emptySet(), stateGenerator);
    }

    /**
     * Creates a successor state, only actually cloning the Node specified by
     * address. Only that node's TimerQueue is cloned. Only that node is
     * configured.
     */
    private SearchState(SearchState previous, Address addressToClone,
                        Event previousEvent) {
        super(previous, addressToClone);

        network = new HashSet<>(previous.network);
        timers = new HashMap<>(previous.timers);
        this.previous = previous;
        this.previousEvent = previousEvent;
        depth = previous.depth + 1;
        newMessages = new HashSet<>();
        newTimers = new HashSet<>();

        timers.put(addressToClone, new TimerQueue(timers.get(addressToClone)));
        configNode(addressToClone);
    }

    /**
     * Creates a shallow clone of this state. Does not actually clone any nodes,
     * messages, timers, etc.
     */
    private SearchState(SearchState source) {
        super(source, null);

        network = new HashSet<>(source.network);
        timers = new HashMap<>(source.timers);
        this.previous = source.previous;
        this.previousEvent = source.previousEvent;
        depth = source.depth;
        newMessages = new HashSet<>(source.newMessages);
        newTimers = new HashSet<>(source.newTimers);
    }

    /**
     * Creates a shallow, copy-on-write clone of this state. Any operations can
     * be done on this clone without affecting this state. (However, these
     * states will share a reference to the previous state.)
     *
     * @return a clone of this state
     */
    @Override
    public SearchState clone() {
        return new SearchState(this);
    }

    @Override
    public Iterable<MessageEnvelope> network() {
        return network;
    }

    @Override
    public Iterable<TimerEnvelope> timers(Address address) {
        return timers.get(address);
    }

    @Override
    protected void setupNode(Address address) {
        Node node = node(address);
        if (node instanceof ClientWorker) {
            if (!((ClientWorker) node).recordResults()) {
                throw new RuntimeException(
                        "Cannot add a ClientWorker that does not store results to SearchState.");
            }
        }

        timers.put(address, new TimerQueue());
        configNode(address);
        node(address).init();
    }

    @Override
    protected void ensureNodeConfig(Address address) {
        configNode(address);
    }

    @Override
    protected void cleanupNode(Address address) {
        throw new UnsupportedOperationException(
                "Cannot remove nodes from search state.");
    }

    private void configNode(final Address address) {
        node(address).config(me -> {
            // Clone on message send
            Message m = Cloning.clone(me.getRight());
            MessageEnvelope messageEnvelope =
                    new MessageEnvelope(me.getLeft(), me.getMiddle(), m);
            network.add(messageEnvelope);
            newMessages.add(messageEnvelope);
        }, me -> {
            // Clone on message send
            Message m = Cloning.clone(me.getRight());
            for (Address to : me.getMiddle()) {
                MessageEnvelope messageEnvelope =
                        new MessageEnvelope(me.getLeft(), to, m);
                network.add(messageEnvelope);
                newMessages.add(messageEnvelope);
            }
        }, te -> {
            // Clone on timer set
            Timer t = Cloning.clone(te.getMiddle());
            Pair<Integer, Integer> bounds = te.getRight();
            TimerEnvelope timerEnvelope =
                    new TimerEnvelope(te.getLeft(), t, bounds.getLeft(),
                            bounds.getRight());
            timers.get(timerEnvelope.to().rootAddress()).add(timerEnvelope);
            newTimers.add(timerEnvelope);
        });
    }

    Collection<Event> events(SearchSettings settings) {
        if (settings == null) {
            settings = new SearchSettings();
        }

        List<Event> events = new LinkedList<>();

        // These checks MUST stay in-sync with the individual step methods

        // Deliver all possible messages
        for (MessageEnvelope message : network) {
            if (hasNode(message.to().rootAddress()) &&
                    settings.shouldDeliver(message)) {
                events.add(new Event(message));
            }
        }

        if (settings.deliverTimers()) {
            // Deliver all possible timers
            for (Address address : addresses()) {
                for (TimerEnvelope timer : timers.get(address).deliverable()) {
                    events.add(new Event(timer));
                }
            }
        }

        return events;
    }

    /**
     * Take all possible steps by delivering all possible messages in the
     * network and all possible timers.
     *
     * @return the (possible empty) set of new states
     */
    Collection<SearchState> step(SearchSettings settings) {
        if (settings == null) {
            settings = new SearchSettings();
        }

        List<SearchState> newStates = new LinkedList<>();

        for (Event event : events(settings)) {
            // TODO: make sure skipping checks is fine
            newStates.add(stepEvent(event, settings, true));
        }

        return newStates;
    }

    public SearchState stepEvent(Event event, SearchSettings settings,
                                 boolean skipChecks) {
        // TODO: use enum for event type

        if (event.isMessage()) {
            return stepMessage(event.message(), settings, skipChecks);
        }
        if (event.isTimer()) {
            return stepTimer(event.timer(), settings, skipChecks);
        }

        return null;
    }

    public SearchState stepMessage(MessageEnvelope message,
                                   SearchSettings settings,
                                   boolean skipChecks) {
        if (settings == null) {
            settings = new SearchSettings();
        }

        Address toAddress = message.to().rootAddress();

        // Node must exist
        if (!hasNode(message.to().rootAddress()) || (!skipChecks &&
                !(network.contains(message) &&
                        settings.shouldDeliver(message)))) {
            return null;
        }

        SearchState ns = new SearchState(this, toAddress, new Event(message));
        Message nm = Cloning.clone(message.message());
        Node n = ns.node(toAddress);

        // Just handle, don't remove since messages can be duplicated.
        n.handleMessage(nm, message.from(), message.to());
        return ns;
    }

    public SearchState stepTimer(TimerEnvelope timer, SearchSettings settings,
                                 boolean skipChecks) {
        if (settings == null) {
            settings = new SearchSettings();
        }

        Address toAddress = timer.to().rootAddress();

        if (!hasNode(timer.to().rootAddress()) || (!skipChecks &&
                !(settings.deliverTimers() &&
                        timers.get(toAddress).isDeliverable(timer)))) {
            return null;
        }

        SearchState ns = new SearchState(this, toAddress, new Event(timer));
        Timer nt = Cloning.clone(timer.timer());
        Node n = ns.node(toAddress);

        n.onTimer(nt, timer.to());
        ns.timers.get(toAddress).remove(timer);
        return ns;
    }

    public Iterable<SearchState> trace() {
        List<SearchState> trace = new LinkedList<>();

        for (SearchState current = this; current != null;
             current = current.previous) {
            trace.add(current);
        }

        Collections.reverse(trace);

        return trace;
    }

    public static Iterable<SearchState> humanReadableTrace(
            @NonNull final SearchState stateToTransform) {
        List<SearchState> originalTrace =
                Lists.newArrayList(stateToTransform.trace());

        @RequiredArgsConstructor
        class GraphNode {
            final Set<GraphNode> next = new HashSet<>();
            final Set<GraphNode> previous = new HashSet<>();
            final Event event;
        }

        // Build up graph of events
        Map<MessageEnvelope, GraphNode> whenSent = new HashMap<>();
        Map<Address, GraphNode> lastStep = new HashMap<>();
        List<GraphNode> initSteps = new ArrayList<>();

        for (int i = 1; i < originalTrace.size(); i++) {
            SearchState state = originalTrace.get(i);
            Event event = state.previousEvent;
            GraphNode node = new GraphNode(event);

            if (event.isMessage()) {
                MessageEnvelope me = event.message();
                if (whenSent.containsKey(me)) {
                    GraphNode p = whenSent.get(me);
                    p.next.add(node);
                    node.previous.add(p);
                }
            }

            Address a = event.locationRootAddress();
            if (lastStep.containsKey(a)) {
                GraphNode p = lastStep.get(a);
                p.next.add(node);
                node.previous.add(p);
            }

            lastStep.put(a, node);

            for (MessageEnvelope me : state.newMessages) {
                if (!whenSent.containsKey(me)) {
                    whenSent.put(me, node);
                }
            }

            if (node.previous.isEmpty()) {
                initSteps.add(node);
            }
        }

        List<Event> events = new ArrayList<>();
        Stack<GraphNode> stack = new Stack<>();

        Collections.reverse(initSteps);
        for (GraphNode node : initSteps) {
            stack.push(node);
        }

        // Do depth-first traversal of graph
        while (!stack.isEmpty()) {
            GraphNode node = stack.pop();
            events.add(node.event);

            for (GraphNode next : node.next) {
                next.previous.remove(node);

                if (next.previous.isEmpty()) {
                    stack.push(next);
                }
            }
        }

        SearchState initialState = originalTrace.get(0);

        List<SearchState> newTrace = new ArrayList<>();
        newTrace.add(initialState);
        SearchState previous = initialState;
        for (Event event : events) {
            SearchState next = previous.stepEvent(event, null, true);

            if (next == null) {
                LOG.severe(
                        "Taking event when generating human-readable trace resulted in null state, returning original trace");
                return originalTrace;
            }

            // Don't take null events ever
            if (next.equals(previous)) {
                continue;
            }

            newTrace.add(next);
            previous = next;
        }

        // TODO: assert resulting states are the same

        return newTrace;
    }

    public static SearchState humanReadableTraceEndState(
            final SearchState stateToTransform) {
        return Iterables.getLast(humanReadableTrace(stateToTransform));
    }

    public void printTrace(PrintStream out) {
        for (SearchState state : trace()) {
            if (state.previousEvent != null) {
                out.println("\t" + state.previousEvent);
            }

            out.println(state);
        }
    }

    public void printTrace() {
        printTrace(System.err);
    }

    @Override
    public String toString() {
        return String.format("State(nodes={%s}, network=%s, timers=%s)",
                Streams.stream(addresses())
                       .map(a -> String.format("%s=%s", a, node(a)))
                       .collect(Collectors.joining(", ")), network, timers);
    }
}