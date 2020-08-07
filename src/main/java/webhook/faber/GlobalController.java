package webhook.faber;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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

    @GetMapping("/invitations")
    public String createInvitationHandler() throws Exception{
        // STEP.1 - create connection F & send invitation
        return globalService.getInvitation();
    }
}
