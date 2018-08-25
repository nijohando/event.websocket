package jp.nijohando.event.websocket;

import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;

public interface PongMessageHandler extends MessageHandler.Whole<PongMessage> {
}
