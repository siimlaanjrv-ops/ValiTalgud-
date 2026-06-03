package ee.bcs.valitalgud.service;

import ee.bcs.valitalgud.controller.contact.dto.ContactRequestDto;
import ee.bcs.valitalgud.infrastructure.error.ErrorResponse;
import ee.bcs.valitalgud.infrastructure.exception.BadRequestException;
import ee.bcs.valitalgud.infrastructure.exception.ServiceUnavailableException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${contact.to-email:}")
    private String toEmail;

    public void sendContactMessage(ContactRequestDto request) {
        validateRequest(request);
        sendEmail(request);
    }

    private void validateRequest(ContactRequestDto request) {
        if (request == null
                || isBlank(request.getNameOrCompany())
                || isBlank(request.getEmail())
                || isBlank(request.getMessage())) {
            throw new BadRequestException(ErrorResponse.CONTACT_FIELDS_REQUIRED);
        }
        if (!EMAIL_PATTERN.matcher(request.getEmail().strip()).matches()) {
            throw new BadRequestException(ErrorResponse.INVALID_EMAIL_FORMAT);
        }
    }

    private void sendEmail(ContactRequestDto request) {
        String nameOrCompany = request.getNameOrCompany().strip();
        String senderEmail = request.getEmail().strip();
        String message = request.getMessage().strip();
        try {
            MimeMessage mimeMessage = buildMimeMessage(nameOrCompany, senderEmail, message);
            mailSender.send(mimeMessage);
        } catch (MessagingException ex) {
            log.error("Kontaktivormi meili saatmine ebaõnnestus", ex);
            throw new ServiceUnavailableException(ErrorResponse.CONTACT_REQUEST_FAILED);
        }
    }

    private MimeMessage buildMimeMessage(String nameOrCompany, String senderEmail, String message) throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper mimeMessageHelper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        mimeMessageHelper.setFrom(fromEmail);
        mimeMessageHelper.setTo(toEmail);
        mimeMessageHelper.setReplyTo(senderEmail);
        mimeMessageHelper.setSubject("Sissetulnud päring - " + nameOrCompany);
        mimeMessageHelper.setText(buildTextBody(nameOrCompany, senderEmail, message), false);
        mimeMessageHelper.setText(buildHtmlBody(nameOrCompany, senderEmail, message), true);
        return mimeMessage;
    }

    private String buildHtmlBody(String nameOrCompany, String senderEmail, String message) {
        return """
                <div style="font-family: Arial, Helvetica, sans-serif; max-width: 560px; margin: 0 auto; \
                border: 3px solid #000; box-shadow: 6px 6px 0 #000;">
                  <div style="background:#000; color:#ffe156; padding:16px 20px; font-size:20px; font-weight:bold;">
                    Sissetulnud päring
                  </div>
                  <div style="padding: 20px; color:#000;">
                    <p style="margin:0 0 14px;"><strong>Nimi/Ettevõte:</strong><br>%s</p>
                    <p style="margin:0 0 14px;"><strong>E-mail:</strong><br><a href="mailto:%s">%s</a></p>
                    <p style="margin:0 0 6px;"><strong>Sõnum:</strong></p>
                    <div style="border:2px solid #000; padding:12px; background:#fdf6e3; white-space:pre-wrap;">%s</div>
                  </div>
                </div>
                """.formatted(
                escapeHtml(nameOrCompany),
                escapeHtml(senderEmail),
                escapeHtml(senderEmail),
                nl2br(escapeHtml(message)));
    }

    private String buildTextBody(String nameOrCompany, String senderEmail, String message) {
        return """
                Sissetulnud päring

                Nimi/Ettevõte: %s
                E-mail: %s

                Sõnum:
                %s
                """.formatted(nameOrCompany, senderEmail, message);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String escapeHtml(String input) {
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String nl2br(String input) {
        return input.replace("\n", "<br>");
    }
}