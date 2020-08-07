package webhook.faber;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import utils.Common;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        // Library logger setup - ERROR|WARN|INFO|DEBUG|TRACE
        Common.setLibraryLogger("ERROR");

        // InitService.initialize () is automatically called when this application starts.
        SpringApplication.run(Application.class, args);
    }
}
