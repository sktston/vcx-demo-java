package webhook.alice;

public class NotificationsRequestDto {
    private String msgUid;
    private String msgType;
    private String theirPwDid;
    private String msgStatusCode;
    private String notificationId;
    private String pwDid;

    public NotificationsRequestDto() {
    }

    public NotificationsRequestDto(String msgUid, String msgType, String theirPwDid, String msgStatusCode, String notificationId, String pwDid) {
        this.msgUid = msgUid;
        this.msgType = msgType;
        this.theirPwDid = theirPwDid;
        this.msgStatusCode = msgStatusCode;
        this.notificationId = notificationId;
        this.pwDid = pwDid;
    }

    public String getMsgUid() {
        return msgUid;
    }

    public String getMsgType() {
        return msgType;
    }

    public String getTheirPwDid() {
        return theirPwDid;
    }

    public String getMsgStatusCode() {
        return msgStatusCode;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public String getPwDid() {
        return pwDid;
    }

    @Override
    public String toString() {
        return "NotificationRequestDto{" +
                "msgUid='" + msgUid + '\'' +
                ", msgType='" + msgType + '\'' +
                ", theirPwDid='" + theirPwDid + '\'' +
                ", msgStatusCode='" + msgStatusCode + '\'' +
                ", notificationId='" + notificationId + '\'' +
                ", pwDid='" + pwDid + '\'' +
                '}';
    }
}
