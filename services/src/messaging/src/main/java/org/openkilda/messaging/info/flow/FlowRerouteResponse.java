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

package org.openkilda.messaging.info.flow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openkilda.messaging.Utils;
import org.openkilda.messaging.info.event.PathInfoData;

public class FlowRerouteResponse extends FlowPathResponse {

    @JsonProperty("rerouted")
    private boolean rerouted;

    @JsonCreator
    public FlowRerouteResponse(
            @JsonProperty(Utils.PAYLOAD) PathInfoData payload,
            @JsonProperty("rerouted") boolean rerouted) {
        super(payload);
        this.rerouted = rerouted;
    }

    public boolean isRerouted() {
        return rerouted;
    }
}
