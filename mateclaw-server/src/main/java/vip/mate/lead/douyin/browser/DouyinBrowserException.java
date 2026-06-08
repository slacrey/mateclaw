package vip.mate.lead.douyin.browser;

public class DouyinBrowserException extends RuntimeException {
    private final String code;

    public DouyinBrowserException(String code, String message) {
        super((code == null || code.isBlank() ? "BROWSER_ERROR" : code) + ": " + (message == null ? "" : message));
        this.code = code == null || code.isBlank() ? "BROWSER_ERROR" : code;
    }

    public String code() {
        return code;
    }
}
