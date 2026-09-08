import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import java.io.FileInputStream;
import java.security.Security;
import java.util.HexFormat;

/** Verify the pinned official release and its signing subkey binding, independently of GitHub. */
class VerifyPgp {
    static final String ROOT = "A7835DFCACA14BDA3CD6EDD3633C6920FBE6A2FF";
    static final String SIGNER = "860E23C74CB7624EF200C1B9361836A70415253D";

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        PGPPublicKeyRingCollection rings;
        try (var input = PGPUtil.getDecoderStream(new FileInputStream(args[0]))) {
            rings = new PGPPublicKeyRingCollection(input, new JcaKeyFingerprintCalculator());
        }
        PGPSignature signature;
        try (var input = PGPUtil.getDecoderStream(new FileInputStream(args[2]))) {
            Object object = new JcaPGPObjectFactory(input).nextObject();
            if (!(object instanceof PGPSignatureList) || ((PGPSignatureList) object).size() != 1)
                throw new SecurityException("Expected a single detached signature");
            signature = ((PGPSignatureList) object).get(0);
        }
        PGPPublicKey signer = rings.getPublicKey(signature.getKeyID());
        if (signer == null) throw new SecurityException("Release signer is absent");
        PGPPublicKey primary = rings.getPublicKeyRing(signer.getKeyID()).getPublicKey();
        if (!fingerprint(primary).equals(ROOT) || !fingerprint(signer).equals(SIGNER))
            throw new SecurityException("Unexpected root or signing key");
        if (primary.hasRevocation() || signer.hasRevocation()) throw new SecurityException("Revoked key");
        for (PGPPublicKey key : new PGPPublicKey[]{primary, signer}) {
            long created = key.getCreationTime().getTime();
            long validity = key.getValidSeconds();
            if (created > System.currentTimeMillis()
                    || (validity > 0 && System.currentTimeMillis() > created + validity * 1000))
                throw new SecurityException("Key is outside its validity period");
        }
        boolean bound = false;
        var bindings = signer.getSignaturesOfType(PGPSignature.SUBKEY_BINDING);
        while (bindings.hasNext()) {
            PGPSignature binding = bindings.next();
            binding.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), primary);
            if (binding.verifyCertification(primary, signer)) bound = true;
        }
        if (!bound) throw new SecurityException("Signing subkey has no valid root binding");
        signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), signer);
        try (var input = new FileInputStream(args[1])) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) signature.update(bytes, 0, count);
        }
        if (!signature.verify()) throw new SecurityException("Invalid detached release signature");
        System.out.println("Official base PGP signature and pinned root-to-subkey binding verified.");
    }

    static String fingerprint(PGPPublicKey key) {
        return HexFormat.of().withUpperCase().formatHex(key.getFingerprint());
    }
}
