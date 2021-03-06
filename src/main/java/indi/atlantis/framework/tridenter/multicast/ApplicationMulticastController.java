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
package indi.atlantis.framework.tridenter.multicast;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 
 * ApplicationMulticastController
 * 
 * @author Fred Feng
 *
 * @since 1.0
 */
@RequestMapping("/application/cluster")
@RestController
public class ApplicationMulticastController {

	@Autowired
	private ApplicationMulticastGroup multicastGroup;

	@Value("${spring.application.name}")
	private String applicationName;

	@GetMapping("/multicast")
	public ResponseEntity<String> multicast(@RequestParam(name = "t", required = false, defaultValue = "*") String topic,
			@RequestParam("c") String content) {
		multicastGroup.multicast(topic, content);
		return ResponseEntity.ok("ok");
	}

	@GetMapping("/unicast")
	public ResponseEntity<String> unicast(@RequestParam(name = "t", required = false, defaultValue = "*") String topic,
			@RequestParam("c") String content) {
		multicastGroup.unicast(topic, content);
		return ResponseEntity.ok("ok");
	}

}
