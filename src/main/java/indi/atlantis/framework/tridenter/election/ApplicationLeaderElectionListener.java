/**
* Copyright 2018-2021 Fred Feng (paganini.fy@gmail.com)

* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package indi.atlantis.framework.tridenter.election;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;

import indi.atlantis.framework.tridenter.multicast.ApplicationMulticastEvent;
import indi.atlantis.framework.tridenter.multicast.ApplicationMulticastEvent.MulticastEventType;

/**
 * 
 * ApplicationLeaderElectionListener
 *
 * @author Fred Feng
 * @version 1.0
 */
public class ApplicationLeaderElectionListener implements LeaderElectionListener, ApplicationListener<ApplicationMulticastEvent> {

	@Autowired
	private LeaderElection leaderElection;

	@Override
	public void onApplicationEvent(ApplicationMulticastEvent applicationEvent) {
		if (applicationEvent.getMulticastEventType() == MulticastEventType.ON_ACTIVE) {
			leaderElection.onTriggered(applicationEvent);
		}
	}

}
