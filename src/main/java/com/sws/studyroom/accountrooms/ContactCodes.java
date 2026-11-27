package com.sws.studyroom.accountrooms;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/** Session-local verification for the explicitly simulated course delivery channel. */
final class ContactCodes {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern PHONE = Pattern.compile("1[3-9][0-9]{9}");
    private static final long TTL = 300_000;
    private static final long RESEND = 30_000;

    record Contact(String value, boolean email) {}
    private record Code(String contact, String value, long createdAt, int failures) {}

    private ContactCodes() {}

    static Contact parse(String value) {
        if (PHONE.matcher(value).matches()) return new Contact(value,false);
        if (value.length() <= 254 && EMAIL.matcher(value).matches())
            return new Contact(value.toLowerCase(Locale.ROOT),true);
        throw new IllegalArgumentException("请输入有效的手机号或邮箱");
    }

    static String issue(HttpSession session, String purpose, Contact contact) {
        String key = ContactCodes.class.getName() + "." + purpose;
        long now = System.currentTimeMillis();
        synchronized (session) {
            if (session.getAttribute(key) instanceof Code old && now - old.createdAt() < RESEND)
                throw new IllegalArgumentException("请等待 30 秒后再发送验证码");
            String value = String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));
            session.setAttribute(key,new Code(contact.value(),value,now,0));
            return value;
        }
    }

    static void consume(HttpSession session, String purpose, Contact contact, String input) {
        String key = ContactCodes.class.getName() + "." + purpose;
        if (session == null) throw new IllegalArgumentException("请先发送验证码");
        synchronized (session) {
            if (!(session.getAttribute(key) instanceof Code code) || !code.contact().equals(contact.value())
                    || System.currentTimeMillis() - code.createdAt() >= TTL || code.failures() >= 5)
                throw new IllegalArgumentException("验证码已失效，请重新发送");
            if (!MessageDigest.isEqual(code.value().getBytes(StandardCharsets.UTF_8),input.getBytes(StandardCharsets.UTF_8))) {
                session.setAttribute(key,new Code(code.contact(),code.value(),code.createdAt(),code.failures()+1));
                throw new IllegalArgumentException(code.failures()==4?"验证码尝试次数已用完":"验证码不正确");
            }
            session.removeAttribute(key);
        }
    }
}
