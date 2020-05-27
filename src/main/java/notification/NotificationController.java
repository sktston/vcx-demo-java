package notification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import utils.Common;

import java.util.logging.Logger;
import static utils.Common.prettyJson;

@RestController
public class NotificationController {
    // get logger for demo - INFO configured
    static final Logger logger = Common.getDemoLogger();

    @PostMapping("/notifications/{agentId}")
    public ResponseEntity notificationsAgentIdHandler(@PathVariable String agentId,
                                                      @RequestBody(required = false) String body) {
        logger.info("[" + java.time.LocalDateTime.now() + "] " + agentId + ": " + prettyJson(body));
        return ResponseEntity.ok().build();
    }
}
