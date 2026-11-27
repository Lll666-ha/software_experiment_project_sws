package com.sws.studyroom.usagefeedback;

import com.sws.studyroom.common.SessionIdentity;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import static com.sws.studyroom.common.Sql.query;
import static com.sws.studyroom.common.Sql.rows;

final class FeedbackAttachments {
    private static final int MB = 1024 * 1024;
    record Upload(Part part, String type) {}

    private FeedbackAttachments() {}

    static List<Upload> validate(Collection<Part> parts, boolean appeal) throws IOException {
        var uploads = new ArrayList<Upload>();
        int images = 0, documents = 0;
        for (Part part : parts) {
            if (!"attachments".equals(part.getName()) || part.getSubmittedFileName() == null
                    || part.getSubmittedFileName().isBlank()) continue;
            final String type = part.getContentType();
            String filename = part.getSubmittedFileName().toLowerCase(Locale.ROOT);
            boolean pdf = "application/pdf".equals(type);
            boolean png = "image/png".equals(type);
            boolean jpeg = "image/jpeg".equals(type);
            if (!(png && filename.endsWith(".png") || jpeg && (filename.endsWith(".jpg") || filename.endsWith(".jpeg"))
                    || appeal && pdf && filename.endsWith(".pdf"))) throw new IllegalArgumentException("附件格式仅支持 JPEG、PNG，申诉还支持 PDF");
            if (part.getSize() < 12 || part.getSize() > (pdf ? 10L : 5L) * MB)
                throw new IllegalArgumentException("图片最大 5 MB，PDF 最大 10 MB");
            if (pdf ? ++documents > 6 : ++images > 6) throw new IllegalArgumentException("图片与 PDF 各最多上传 6 件");
            try (var input = part.getInputStream()) {
                if (pdf) {
                    if (!new String(input.readNBytes(8), StandardCharsets.US_ASCII).matches("%PDF-[12]\\.[0-9]"))
                        throw new IllegalArgumentException("PDF 文件内容与格式不一致");
                    long tail = Math.min(1024,part.getSize()-8);
                    input.skipNBytes(part.getSize()-8-tail);
                    if (!new String(input.readNBytes((int)tail),StandardCharsets.US_ASCII).stripTrailing().endsWith("%%EOF"))
                        throw new IllegalArgumentException("PDF 文件不完整");
                } else {
                    try (var image = new javax.imageio.stream.MemoryCacheImageInputStream(input)) {
                        var readers = javax.imageio.ImageIO.getImageReaders(image);
                        if (!readers.hasNext()) throw new IllegalArgumentException("无法识别图片内容");
                        var reader = readers.next();
                        try {
                            if (!(png ? "png" : "jpeg").equalsIgnoreCase(reader.getFormatName()))
                                throw new IllegalArgumentException("图片内容与格式不一致");
                            reader.setInput(image,true,true);
                            if (reader.getWidth(0) < 1 || reader.getHeight(0) < 1)
                                throw new IllegalArgumentException("图片尺寸无效");
                        } finally {reader.dispose();}
                    }
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException("附件无法读取或内容已损坏", ex);
            }
            uploads.add(new Upload(part,type));
        }
        return uploads;
    }

    static void save(Connection c, long record, boolean appeal, List<Upload> files) throws SQLException {
        for (Upload file : files) {
            try (var input = file.part().getInputStream();
                 var insert = query(c,"INSERT INTO feedback_attachment(review_id,appeal_id,media_type,byte_count,payload) VALUES (?,?,?,?,?)",
                         appeal?null:record,appeal?record:null,file.type(),file.part().getSize())) {
                insert.setBinaryStream(5,input,file.part().getSize());
                insert.executeUpdate();
            } catch (IOException ex) {
                throw new SQLException("无法保存附件",ex);
            }
        }
    }

    static List<java.util.Map<String,Object>> list(Connection c,long record,boolean appeal) throws SQLException {
        return rows(c,"SELECT attachment_id,media_type FROM feedback_attachment WHERE "
                +(appeal?"appeal_id":"review_id")+"=? ORDER BY attachment_id",record);
    }

    static void download(Connection c,SessionIdentity actor,long id,HttpServletResponse response) throws SQLException, IOException {
        String sql = """
                SELECT f.media_type,f.byte_count,f.payload
                FROM feedback_attachment f LEFT JOIN review v ON v.review_id=f.review_id
                LEFT JOIN appeal a ON a.appeal_id=f.appeal_id
                JOIN booking b ON b.booking_id=COALESCE(v.booking_id,a.booking_id)
                JOIN room r ON r.room_id=b.room_id
                WHERE f.attachment_id=? AND (
                    f.review_id IS NOT NULL AND r.listing_status='LISTED'
                    OR b.user_id=? OR ? OR r.manager_user_id=? AND ?)
                """;
        try (var p=query(c,sql,id,actor.userId(),actor.role()==SessionIdentity.Role.SUPER_ADMIN,
                actor.userId(),actor.role()==SessionIdentity.Role.ROOM_ADMIN);
             var result=p.executeQuery()) {
            if (!result.next()) throw new UsageService.Failure(404,"附件不存在或无权访问");
            String type=result.getString(1);
            String suffix=switch(type){case "image/png"->"png";case "image/jpeg"->"jpg";default->"pdf";};
            response.setContentType(type);
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Content-Security-Policy","default-src 'none'; sandbox");
            response.setHeader("Content-Disposition","attachment; filename=attachment-"+id+"."+suffix);
            response.setContentLength(result.getInt(2));
            try (var input=result.getBinaryStream(3)) {input.transferTo(response.getOutputStream());}
        }
    }
}
