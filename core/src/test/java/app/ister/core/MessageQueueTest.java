package app.ister.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class MessageQueueTest {

    @Test
    void privateConstructorThrowsIllegalStateException() throws Exception {
        Constructor<MessageQueue> constructor = MessageQueue.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException exception = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    @Test
    void constantsAreDefined() {
        assertNotNull(MessageQueue.APP_ISTER_SERVER_EPISODE_FOUND);
        assertNotNull(MessageQueue.APP_ISTER_SERVER_SHOW_FOUND);
        assertNotNull(MessageQueue.APP_ISTER_SERVER_MOVIE_FOUND);
        assertNotNull(MessageQueue.APP_ISTER_SERVER_MEDIA_FILE_FOUND);
        assertNotNull(MessageQueue.APP_ISTER_SERVER_IMAGE_FOUND);
    }
}
