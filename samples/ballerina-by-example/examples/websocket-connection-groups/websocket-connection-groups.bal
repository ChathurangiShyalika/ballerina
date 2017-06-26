import ballerina.lang.system;
import ballerina.lang.messages;
import ballerina.net.http;
import ballerina.net.ws;
import ballerina.lang.jsons;
import ballerina.doc;
import samples.post_m1.data_types.json;

@http:BasePath {value:"/endpoint"}
@ws:WebSocketUpgradePath {value:"/ws"}
service echoServer {

    int i = 0;
    string groupEven = "even";
    string groupOdd = "odd";

    @ws:OnOpen {}
    resource onOpen(message m) {
        if (i % 2 == 0) {
            ws:addConnectionToGroup(groupEven);
        } else {
            ws:addConnectionToGroup(groupOdd);
        }
        i = i + 1;
    }

    @ws:OnTextMessage {}
    resource onTextMessage(message m) {
        json jsonPayload = messages:getJsonPayload(m);
        string command = jsonPayload["command"];
        string groupName = jsonPayload["group"];
        string msg =jsonPayload["msg"];

        if ("send" == command) {
            // broadcast text to given connection group
            ws:pushTextToGroup(groupName, msg);
        } else if ("remove" == command) {
            // remove connection from the mentioned group
            ws:removeStoredConnection(id);
        } else if ("removeGroup" == command) {
            // remove the connection group
            ws:removeConnectionGroup(groupName);
        }
        }
    }

    @ws:OnClose {}
    resource onClose(message m) {
        // broadcast text to all connected clients
        ws:broadcastText("Client left");
    }
}
