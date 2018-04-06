/* Copyright 2017 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.wfm.topology.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.storm.state.InMemoryKeyValueState;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.openkilda.messaging.BaseMessage;
import org.openkilda.messaging.Destination;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.Utils;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.NetworkCommandData;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.flow.FlowRestoreRequest;
import org.openkilda.messaging.ctrl.AbstractDumpState;
import org.openkilda.messaging.ctrl.state.CacheBoltState;
import org.openkilda.messaging.ctrl.state.FlowDump;
import org.openkilda.messaging.ctrl.state.NetworkDump;
import org.openkilda.messaging.error.CacheException;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.discovery.ChunkDescriptor;
import org.openkilda.messaging.info.discovery.NetworkInfoData;
import org.openkilda.messaging.info.event.*;
import org.openkilda.messaging.info.flow.FlowInfoData;
import org.openkilda.messaging.info.flow.FlowOperation;
import org.openkilda.messaging.model.Flow;
import org.openkilda.messaging.model.ImmutablePair;
import org.openkilda.messaging.payload.flow.FlowState;
import org.openkilda.pce.cache.Cache;
import org.openkilda.pce.cache.FlowCache;
import org.openkilda.pce.cache.NetworkCache;
import org.openkilda.pce.cache.ResourceCache;
import org.openkilda.wfm.ctrl.CtrlAction;
import org.openkilda.wfm.ctrl.ICtrlBolt;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.cache.service.CacheWarmingService;
import org.openkilda.wfm.topology.utils.AbstractTickStatefulBolt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.openkilda.messaging.Utils.MAPPER;

public class ReductionCacheBolt
        extends AbstractTickStatefulBolt<InMemoryKeyValueState<String, Cache>>
        implements ICtrlBolt {
    public static final String STREAM_ID_CTRL = "ctrl";
    private static final String NETWORK_CACHE = "network";
    private static final String FLOW_CACHE = "flow";
    private static final Logger logger = LoggerFactory.getLogger(ReductionCacheBolt.class);
    private InMemoryKeyValueState<String, Cache> state;
    private final Map<String, Set<String>> reroutedFlows = new ConcurrentHashMap<>();
    private TopologyContext context;
    private OutputCollector outputCollector;
    /**
     * {@inheritDoc}
     */
    @Override
    public void __doWork(Tuple tuple) {

        BaseMessage bm = MAPPER.readValue(json, BaseMessage.class);
        if (bm instanceof InfoMessage == false) {
            return;
        }
        InfoMessage message = (InfoMessage) bm;
        InfoData data = message.getData();

        if (data instanceof SwitchInfoData) {
            logger.info("Cache update switch info data: {}", data);
            handleSwitchEvent((SwitchInfoData) data, tuple);

        } else if (data instanceof IslInfoData) {
            logger.info("Cache update isl info data: {}", data);
            handleIslEvent((IslInfoData) data, tuple);

        } else if (data instanceof PortInfoData) {
            logger.info("Cache update port info data: {}", data);
            handlePortEvent((PortInfoData) data, tuple);

        } else if (data instanceof NetworkTopologyChange) {
            logger.info("Switch flows reroute request");

            NetworkTopologyChange topologyChange = (NetworkTopologyChange) data;
            handleNetworkTopologyChangeEvent(topologyChange, tuple);
        }
    }

    private void handleSwitchEvent(SwitchInfoData sw, Tuple tuple) throws IOException {
        logger.info("State update switch {} message {}", sw.getSwitchId(), sw.getState());
        Set<ImmutablePair<Flow, Flow>> affectedFlows;

        switch (sw.getState()) {
            case REMOVED:
            case DEACTIVATED:
                affectedFlows = flowCache.getActiveFlowsWithAffectedPath(sw.getSwitchId());
                String reason = String.format("switch %s is %s", sw.getSwitchId(), sw.getState());
                emitRerouteCommands(affectedFlows, tuple, UUID.randomUUID().toString(), FlowOperation.UPDATE, reason);
                break;
        }
    }

    private void handleIslEvent(IslInfoData isl, Tuple tuple) {
        logger.info("State update isl {} message cached {}", isl.getId(), isl.getState());
        Set<ImmutablePair<Flow, Flow>> affectedFlows;

        switch (isl.getState()) {
            case FAILED:
                try {
                    networkCache.deleteIsl(isl.getId());
                } catch (CacheException exception) {
                    logger.warn("{}:{}", exception.getErrorMessage(), exception.getErrorDescription());
                }

                affectedFlows = flowCache.getActiveFlowsWithAffectedPath(isl);
                String reason = String.format("isl %s FAILED", isl.getId());
                emitRerouteCommands(affectedFlows, tuple, UUID.randomUUID().toString(),
                        FlowOperation.UPDATE, reason);
                break;
        }
    }

    private void handlePortEvent(PortInfoData port, Tuple tuple) {
        switch (port.getState()) {
            case DOWN:
            case DELETE:
                Set<ImmutablePair<Flow, Flow>> affectedFlows = flowCache.getActiveFlowsWithAffectedPath(port);
                String reason = String.format("port %s_%s is %s", port.getSwitchId(), port.getPortNo(), port.getState());
                emitRerouteCommands(affectedFlows, tuple, UUID.randomUUID().toString(), FlowOperation.UPDATE, reason);
                break;
        }
    }

    private void handleNetworkTopologyChangeEvent(NetworkTopologyChange topologyChange, Tuple tuple) {
        Set<ImmutablePair<Flow, Flow>> affectedFlows;

        switch (topologyChange.getType()) {
            case ENDPOINT_DROP:
                // TODO(surabujin): need implementation
                return;

            case ENDPOINT_ADD:
                affectedFlows = getFlowsForRerouting(topologyChange);
                break;

        }
        String reason = String.format("network topology change  %s_%s is %s",
                topologyChange.getSwitchId(), topologyChange.getPortNumber(),
                topologyChange.getType());
        emitRerouteCommands(affectedFlows, tuple, UUID.randomUUID().toString(),
                FlowOperation.UPDATE, reason);
    }

    private void emitFlowCrudMessage(InfoData data, Tuple tuple, String correlationId) throws IOException {
        Message message = new InfoMessage(data, System.currentTimeMillis(),
                correlationId, Destination.WFM);
        outputCollector.emit(StreamType.WFM_DUMP.toString(), tuple, new Values(MAPPER.writeValueAsString(message)));
        logger.info("Flow command message sent");
    }


    private void emitRerouteCommands(Set<ImmutablePair<Flow, Flow>> flows, Tuple tuple,
                                     String correlationId, FlowOperation operation, String reason) {
        for (ImmutablePair<Flow, Flow> flow : flows) {
            try {
                flow.getLeft().setState(FlowState.DOWN);
                flow.getRight().setState(FlowState.DOWN);
                FlowRerouteRequest request = new FlowRerouteRequest(flow.getLeft(), operation);

                Values values = new Values(Utils.MAPPER.writeValueAsString(new CommandMessage(
                        request, System.currentTimeMillis(), correlationId, Destination.WFM)));
                outputCollector.emit(StreamType.WFM_DUMP.toString(), tuple, values);

                logger.warn("Flow {} reroute command message sent with correlationId {} reason {}",
                        flow.getLeft().getFlowId(), correlationId, reason);
            } catch (JsonProcessingException exception) {
                logger.error("Could not format flow reroute request by flow={}", flow, exception);
            }
        }
    }


    /*
     * getFlowsForRerouting (NetTopoChg) -> inactive (DOWN) flows -> union of inactive and TransitFlows
     * getTransitFlowsPreviouslyInstalled (sw) -> set (flows)
     */

    private Set<ImmutablePair<Flow, Flow>> getFlowsForRerouting(NetworkTopologyChange rerouteData) {
        Set<ImmutablePair<Flow, Flow>> inactiveFlows = flowCache.dumpFlows().stream()
                .filter(flow -> FlowState.DOWN.equals(flow.getLeft().getState()))
                .collect(Collectors.toSet());

        // would have to figure out transit flows .. but flowcache down .. ** needs to be updated based on network state **
        //Set<ImmutablePair<Flow, Flow>> transitFlows = getTransitFlowsPreviouslyInstalled(rerouteData.getSwitchId());
        return Sets.union(inactiveFlows, transitFlows);

    }

}
