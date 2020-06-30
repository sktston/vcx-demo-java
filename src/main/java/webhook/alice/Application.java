package webhook.alice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // GlobalService.initialize () is automatically called before application ready.
        SpringApplication.run(Application.class, args);
        // GlobalService.receiveInvitation() is automatically called when application ready.
    }
}
