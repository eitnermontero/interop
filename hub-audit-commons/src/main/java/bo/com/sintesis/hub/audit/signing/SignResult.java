package bo.com.sintesis.hub.audit.signing;

/**
 * Resultado de la operación de firma del {@code chain_hash}.
 *
 * @param signature  firma en formato base64url producida por Vault Transit;
 *                   cadena vacía cuando la firma está deshabilitada (NoOp)
 * @param keyVersion versión de la clave Vault Transit utilizada para firmar;
 *                   {@code 0} cuando la firma está deshabilitada (NoOp)
 */
public record SignResult(String signature, int keyVersion) {
}
