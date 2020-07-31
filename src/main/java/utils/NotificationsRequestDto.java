package utils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class NotificationsRequestDto {
    private String msgUid;
    private String msgType;
    private String theirPwDid;
    private String msgStatusCode;
    private String notificationId;
    private String pwDid;
}
