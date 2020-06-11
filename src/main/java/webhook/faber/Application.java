package webhook.faber;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        // InitService.initialize () is automatically called when this application starts.
        SpringApplication.run(Application.class, args);
    }
}
