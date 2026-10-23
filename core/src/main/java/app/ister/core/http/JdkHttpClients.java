package app.ister.core.http;

import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The one place that builds outgoing HTTP clients, so that every blocking HTTP call in the server
 * goes through {@link java.net.http.HttpClient}.
 * <p>
 * Why this matters: the server runs Tomcat, the RabbitMQ listeners and the schedulers on virtual
 * threads, as a GraalVM native image. GraalVM's virtual-thread poller sometimes fails to wake a
 * virtual thread that is parked in a blocking socket read although the data has arrived
 * (oracle/graal #13787); the thread only resumes when its read timeout expires. Every client that
 * reads the socket on the calling thread is exposed: {@code HttpURLConnection}
 * ({@code SimpleClientHttpRequestFactory}, {@code URLConnection}, Feign's default client) and
 * Apache HttpClient classic (what a bare {@code RestClient.builder()} picks, because the AWS SDK
 * puts httpclient5 on the classpath). The JDK client does its I/O on its own platform
 * {@code SelectorManager} thread; the calling virtual thread parks on a future and is woken by a
 * plain unpark, which the poller bug does not touch.
 * <p>
 * {@link HttpClient.Redirect#NORMAL} is deliberate: {@code HttpURLConnection} followed redirects
 * silently (Cover Art Archive to archive.org, Wikimedia Commons {@code Special:FilePath}, podcast
 * feeds), while the JDK client defaults to {@code NEVER}.
 */
public final class JdkHttpClients {

    private JdkHttpClients() {
    }

    /** A client with a bounded connect timeout that follows same-protocol redirects. */
    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** A Spring request factory on top of {@link #httpClient(Duration)} with a bounded read timeout. */
    public static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient(connectTimeout));
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
