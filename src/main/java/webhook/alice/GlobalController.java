package webhook.alice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import utils.NotificationsRequestDto;

@RestController
public class GlobalController {
    @Autowired
    GlobalService globalService;

    @PostMapping("/notifications")
    public ResponseEntity notificationsHandler(@RequestBody(required = false) NotificationsRequestDto body) throws Exception {
        globalService.handleMessage(body);
        return ResponseEntity.ok().build();
    }
}
