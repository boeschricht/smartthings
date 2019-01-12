/**
 *  Copyright 2016 AdamV
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
	definition (name: "Smart Color Button PSR04", namespace: "Philio", author: "AdamV") {
		capability "Actuator"
		capability "Switch"
		capability "Switch Level"
		capability "Refresh"
		capability "Sensor"
		capability "Button"
	        capability "Configuration"
	        capability "Battery"

		fingerprint type: "1801", mfr: "013C", prod: "0009", model: "0022"

    }

	simulator {
		status "on":  "command: 2003, payload: FF"
		status "off": "command: 2003, payload: 00"
	}

	tiles {
		multiAttributeTile(name:"button", type:"lighting", width:6, height:4) {
			tileAttribute("device.button", key: "PRIMARY_CONTROL"){
				attributeState "default", label:'Controller', backgroundColor:"#44b621", icon:"st.Home.home30"
				attributeState "held", label: "holding", backgroundColor: "#C390D4"
			}
			tileAttribute ("device.battery", key: "SECONDARY_CONTROL") {
				attributeState "battery", label:'${currentValue} % battery'
			}
			tileAttribute ("device.level", key: "SLIDER_CONTROL") {
				attributeState "level", action:"switch level.setLevel"
			}
			// tileAttribute ("device.level", key: "SECONDARY_CONTROL") {
			// attributeState "level", label: 'Level is ${currentValue}%'
			//}
		}

		standardTile("refresh", "command.refresh", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
			state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
		}
		valueTile("configure", "device.button", width: 2, height: 2, decoration: "flat") {
			state "default", label: "configure", backgroundColor: "#ffffff", action: "configure"
		}


		main "button"
		details (["button", "refresh", "configure"])
	}
}

def parse(String description) {
	log.debug("This is what I receive: $description")
	def result = []
	if (description.startsWith("Err")) {
	    result = createEvent(descriptionText:description, isStateChange:true)
	} else {
		def cmd = zwave.parse(description, [0x20: 1, 0x84: 2])
		if (cmd) {
			result += zwaveEvent(cmd)
		}
	}
	return result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv1.WakeUpNotification cmd) {
	log.debug("cmd: $cmd")

	def result = [createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)]

        // Only ask for battery if we haven't had a BatteryReport in a while
        if (!state.lastbatt || (new Date().time) - state.lastbatt > 24*60*60*1000) {
                result << response(zwave.batteryV1.batteryGet())
                result << response("delay 1200")  // leave time for device to respond to batteryGet
        }
        result << response(zwave.wakeUpV1.wakeUpNoMoreInformation())
        result
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
	log.debug("cmd: $cmd")
	if (cmd.value == 0) {
		createEvent(name: "switch", value: "off")
	} else if (cmd.value == 255) {
		createEvent(name: "switch", value: "on")
	} else {
		[ createEvent(name: "switch", value: "on"), createEvent(name: "switchLevel", value: cmd.value) ]
	}
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
        def map = [ name: "battery", unit: "%" ]
        if (cmd.batteryLevel == 0xFF) {  // Special value for low battery alert
                map.value = 1
                map.descriptionText = "${device.displayName} has a low battery"
                map.isStateChange = true
        } else {
                map.value = cmd.batteryLevel
                log.debug ("Battery: $cmd.batteryLevel")
        }
        // Store time of last battery update so we don't ask every wakeup, see WakeUpNotification handler
        state.lastbatt = new Date().time
        createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	log.debug("cmd: $cmd")
	def encapsulatedCommand = cmd.encapsulatedCommand([0x70: 1, 0x86: 1])
	if (encapsulatedCommand) {
		state.sec = 1
		def result = zwaveEvent(encapsulatedCommand)
		result = result.collect {
			if (it instanceof physicalgraph.device.HubAction && !it.toString().startsWith("9881")) {
				response(cmd.CMD + "00" + it.toString())
			} else {
				it
			}
		}
		result
	}
}

def zwaveEvent(physicalgraph.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
	log.debug("cmd: $cmd")
	log.debug( "keyAttributes: $cmd.keyAttributes")
        log.debug( "sceneNumber: $cmd.sceneNumber")
        log.debug( "sequenceNumber: $cmd.sequenceNumber")
        if ( cmd.sceneNumber == 1 ) {
        	Integer button = 1
    		sendEvent(name: "button", value: "pushed", data: [buttonNumber: button], descriptionText: "$device.displayName button $button was pushed", isStateChange: true)
		log.debug( "Button $button was pushed" )
    	}
       	log.debug( "payload: $cmd.payload")
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
		log.debug( "Value: $cmd.value")
        def dimmerValue = cmd.value
   	if (cmd.value == 0) {
		sendEvent(name: "switch", value: "off")
        log.debug("turned off")
        sendEvent(name: "level", value: dimmerValue)
	} else if (cmd.value == 255) {
		sendEvent(name: "switch", value: "on")
        log.debug("turned on")
	} else {
		sendEvent(name: "switch", value: "on")
        //createEvent(name: "level", value: dimmerValue)
        sendEvent(name: "level", value: dimmerValue)
        log.debug("sent switch on at value $cmd.value")
	}

      // 	log.debug( "payload: $cmd.payload")
}



def zwaveEvent(physicalgraph.zwave.commands.crc16encapv1.Crc16Encap cmd) {
	def versions = [0x31: 2, 0x30: 1, 0x84: 1, 0x9C: 1, 0x70: 2]
	// def encapsulatedCommand = cmd.encapsulatedCommand(versions)
	def version = versions[cmd.commandClass as Integer]
	def ccObj = version ? zwave.commandClass(cmd.commandClass, version) : zwave.commandClass(cmd.commandClass)
	def encapsulatedCommand = ccObj?.command(cmd.command)?.parse(cmd.data)
	if (encapsulatedCommand) {
		zwaveEvent(encapsulatedCommand)
	}
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
	return [createEvent(descriptionText: "$device.displayName: $cmd", displayed: false)]
}

def on() {
	commands([zwave.basicV1.basicSet(value: 0xFF), zwave.basicV1.basicGet()])
}

def off() {
	commands([zwave.basicV1.basicSet(value: 0x00), zwave.basicV1.basicGet()])
}

def refresh() {
	log.debug("her1")
	commands([zwave.basicV1.basicGet().format(),
		zwave.batteryV1.batteryGet().format(),
		zwave.configurationV1.configurationSet(configurationValue: [10], parameterNumber: 10, size: 1).format(),
		zwave.configurationV1.configurationSet(configurationValue: [2], parameterNumber: 25, size: 1).format()
	], 2500)
	delayBetween([
		zwave.configurationV1.configurationSet(configurationValue: [10], parameterNumber: 10, size: 1).format(),
		zwave.configurationV1.configurationSet(configurationValue: [2], parameterNumber: 25, size: 1).format()
		])

}

def configure() {
		delayBetween([
			zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId).format(),
			zwave.associationV2.associationSet(groupingIdentifier: 2, nodeId: zwaveHubNodeId).format(),
			// xxx fjernet liste [] rundt om værdier
			zwave.configurationV1.configurationSet(configurationValue: 10, parameterNumber: 10, size: 1).format(),
			zwave.configurationV1.configurationSet(configurationValue: 2, parameterNumber: 25, size: 1).format()
	        ])
}

def updated() {
	log.debug("her2")
	commands([zwave.basicV1.basicGet().format(), zwave.batteryV1.batteryGet().format()], 2500)
}

def setLevel(value) {
	log.debug("value: $value")
	def valueaux = value as Integer
	def level = Math.max(Math.min(valueaux, 99), 0)
	if (level > 0) {
		sendEvent(name: "switch", value: "on")
	} else {
		sendEvent(name: "switch", value: "off")
	}
	sendEvent(name: "level", value: level, unit: "%")
	commands([zwave.basicV1.basicSet(value: value as Integer), zwave.basicV1.basicGet()], 4000)
}

private command(physicalgraph.zwave.Command cmd) {
	log.debug("cmd: $cmd")
	if (isSecured()) {
		zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		cmd.format()
	}
}

private commands(commands, delay=2000) {
	delayBetween(commands.collect{ command(it) }, delay)
}

private isSecured() {
	if (zwaveInfo && zwaveInfo.zw) {
		log.debug("secure")
		return zwaveInfo.zw.endsWith("s")
	} else {
		log.debug("unsecure")
		return state.sec == 1
	}
}
