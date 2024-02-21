package app.ister.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "app.ister.server")
public class IsterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(IsterServerApplication.class, args);
	}

}
