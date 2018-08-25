package jp.nijohando.event.websocket;

import javax.websocket.MessageHandler;

public interface WholeBinaryMessageHandler extends MessageHandler.Whole<byte[]> {
}
