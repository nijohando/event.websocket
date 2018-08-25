package jp.nijohando.event.websocket;

import javax.websocket.MessageHandler;

public interface WholeTextMessageHandler extends MessageHandler.Whole<String> {
}
