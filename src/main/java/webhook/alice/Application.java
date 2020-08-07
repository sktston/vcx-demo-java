package webhook.alice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import utils.Common;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // Library logger setup - ERROR|WARN|INFO|DEBUG|TRACE
        Common.setLibraryLogger("ERROR");

        // GlobalService.initialize () is automatically called before application ready.
        SpringApplication.run(Application.class, args);
        // GlobalService.receiveInvitation() is automatically called when application ready.
    }
}
