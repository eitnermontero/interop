package bo.com.sintesis.mdqr.base.service;

import bo.com.sintesis.mdqr.base.service.exception.InvalidQrFormatException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Servicio para decodificar códigos QR desde imágenes.
 * <p>
 * Utiliza la librería ZXing para leer QR codes desde:
 * - Imágenes en Base64
 * - Archivos MultipartFile (subidos vía HTTP)
 * </p>
 */
@Service
@Slf4j
public class QrImageDecoderService {

    private final MultiFormatReader reader;
    private final Map<DecodeHintType, Object> hints;

    public QrImageDecoderService() {
        this.reader = new MultiFormatReader();
        this.hints = new HashMap<>();
        // Configurar hints para mejorar la decodificación
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.FALSE);
    }

    /**
     * Decodifica un código QR desde una imagen en Base64.
     *
     * @param base64Image Imagen del QR en formato Base64
     * @return Contenido del código QR (texto)
     * @throws InvalidQrFormatException si no se puede decodificar el QR
     */
    public String decodeFromBase64(String base64Image) {
        log.debug("Decodificando QR desde imagen Base64");

        try {
            // Limpiar el prefijo data:image si existe
            String cleanBase64 = cleanBase64Prefix(base64Image);

            // Decodificar Base64 a bytes
            byte[] imageBytes = Base64.getDecoder().decode(cleanBase64);

            // Convertir bytes a BufferedImage
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));

            if (bufferedImage == null) {
                throw new InvalidQrFormatException("No se pudo leer la imagen Base64. Formato inválido.");
            }

            // Decodificar el QR de la imagen
            return decodeFromBufferedImage(bufferedImage);

        } catch (IllegalArgumentException e) {
            log.error("Error al decodificar Base64: {}", e.getMessage());
            throw new InvalidQrFormatException("El Base64 proporcionado no es válido: " + e.getMessage());

        } catch (IOException e) {
            log.error("Error al leer la imagen desde Base64: {}", e.getMessage());
            throw new InvalidQrFormatException("No se pudo procesar la imagen: " + e.getMessage());
        }
    }

    /**
     * Decodifica un código QR desde un archivo MultipartFile.
     *
     * @param file Archivo de imagen que contiene el QR
     * @return Contenido del código QR (texto)
     * @throws InvalidQrFormatException si no se puede decodificar el QR
     */
    public String decodeFromFile(MultipartFile file) {
        log.debug("Decodificando QR desde archivo: {} ({})", file.getOriginalFilename(), file.getContentType());

        if (file.isEmpty()) {
            throw new InvalidQrFormatException("El archivo está vacío");
        }

        // Validar tipo de contenido
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new InvalidQrFormatException(
                "El archivo debe ser una imagen. Tipo recibido: " + contentType
            );
        }

        try {
            // Convertir MultipartFile a BufferedImage
            BufferedImage bufferedImage = ImageIO.read(file.getInputStream());

            if (bufferedImage == null) {
                throw new InvalidQrFormatException(
                    "No se pudo leer la imagen. Formato no soportado: " + contentType
                );
            }

            // Decodificar el QR de la imagen
            return decodeFromBufferedImage(bufferedImage);

        } catch (IOException e) {
            log.error("Error al leer el archivo de imagen: {}", e.getMessage());
            throw new InvalidQrFormatException("Error al procesar el archivo: " + e.getMessage());
        }
    }

    /**
     * Decodifica un código QR desde un BufferedImage usando ZXing.
     *
     * @param image Imagen que contiene el código QR
     * @return Contenido del código QR (texto)
     * @throws InvalidQrFormatException si no se encuentra un QR en la imagen
     */
    private String decodeFromBufferedImage(BufferedImage image) {
        try {
            // Convertir BufferedImage a BinaryBitmap para ZXing
            BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            // Decodificar el QR
            Result result = reader.decode(bitmap, hints);

            String qrContent = result.getText();
            log.info("QR decodificado exitosamente. Longitud: {} caracteres", qrContent.length());
            log.debug("Contenido del QR: {}", qrContent.substring(0, Math.min(50, qrContent.length())) + "...");

            return qrContent;

        } catch (NotFoundException e) {
            log.warn("No se encontró un código QR en la imagen");
            throw new InvalidQrFormatException(
                "No se pudo detectar un código QR en la imagen. " +
                "Asegúrese de que la imagen contenga un QR válido y esté bien iluminada."
            );

        } catch (Exception e) {
            log.error("Error inesperado al decodificar QR: {}", e.getMessage(), e);
            throw new InvalidQrFormatException("Error al decodificar el código QR: " + e.getMessage());
        }
    }

    /**
     * Limpia el prefijo data:image/... del Base64 si existe.
     *
     * @param base64 String Base64 (puede incluir prefijo data:image)
     * @return Base64 limpio sin prefijo
     */
    private String cleanBase64Prefix(String base64) {
        if (base64.contains(",")) {
            // Formato: data:image/png;base64,iVBORw0KG...
            return base64.substring(base64.indexOf(",") + 1);
        }
        return base64;
    }
}
