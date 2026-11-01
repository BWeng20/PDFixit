package com.bw.pdfixit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tool for signing.<br>
 * Based on PdfBox Example <a href="https://svn.apache.org/repos/asf/pdfbox/trunk/examples/src/main/java/org/apache/pdfbox/examples/signature">Signature</a>.
 */
public class SignatureTool implements SignatureInterface {

    /**
     * Initialize an empty PKCS12 keystore on top of bouncycastle
     *
     * @throws KeyStoreException if the keystore has not been initialized (loaded)
     */
    public SignatureTool()
            throws KeyStoreException, NoSuchProviderException {
        Security.addProvider(new BouncyCastleProvider());
        keystore = KeyStore.getInstance("PKCS12", "BC");

    }

    private final KeyStore keystore;
    private boolean keyStoreLoaded = false;
    private PrivateKey privateKey;
    private Certificate[] certificateChain;

    /**
     * SignatureInterface sample implementation.
     * <p>
     * This method will be called from inside the pdfbox and create the PKCS #7 signature.
     * The given InputStream contains the bytes that are given by the byte range.
     * <p>
     */
    @Override
    public byte[] sign(InputStream content) throws IOException {
        try {
            // Use an CMS (Cryptographic Message Syntax) container to make the PDF happy.
            CMSProcessableByteArray processable = new CMSProcessableByteArray(content.readAllBytes());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC")
                    .build(this.privateKey);

            CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
            gen.addSignerInfoGenerator(
                    new JcaSignerInfoGeneratorBuilder(
                            new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
                    ).build(signer, (X509Certificate) certificateChain[0])
            );

            List<X509Certificate> certList = new ArrayList<>();
            for (Certificate c : certificateChain) {
                certList.add((X509Certificate) c);
            }
            gen.addCertificates(new JcaCertStore(certList));

            CMSSignedData signedData = gen.generate(processable, false);
            return signedData.getEncoded();

        } catch (Exception e) {
            throw new IOException("CMS signing failed", e);
        }
    }

    protected Set<String> getAliases() throws KeyStoreException {
        HashSet<String> s = new HashSet<>();

        if (this.keyStoreLoaded) {
            var e = this.keystore.aliases();
            while (e.hasMoreElements())
                s.add(e.nextElement());
        }
        return s;
    }

    /**
     * Adds a key to the store.
     */
    public String addKey(InputStream is, char[] pin) throws
            CertificateException, IOException, NoSuchAlgorithmException, KeyStoreException {

        Set<String> before = getAliases();
        this.keystore.load(is, pin);
        this.keyStoreLoaded = true;
        Set<String> after = getAliases();

        after.removeAll(before);
        if (after.isEmpty())
            return null;
        else
            return after.iterator().next();
    }

    /**
     * @param pin is the pin for the keystore / private key
     */
    public void addSignature(PDDocument document, String keyAlias, char[] pin)
            throws Exception {

        if (!this.keyStoreLoaded)
            throw new IllegalStateException("Key needs to be loaded before Signature can be added.");

        this.certificateChain = null;
        if (keyAlias == null) {
            keyAlias = this.keystore.aliases().nextElement();
        }
        this.privateKey = (PrivateKey) keystore.getKey(keyAlias, pin);
        if (this.privateKey == null) {
            throw new IOException("Could not find certificate " + keyAlias);
        }

        Certificate[] certChain = keystore.getCertificateChain(keyAlias);
        if (certChain == null) {
            throw new IOException("Could not find certificate " + keyAlias);
        }
        this.certificateChain = certChain;
        Certificate cert = certChain[0];
        if (cert instanceof X509Certificate) {
            // avoid expired certificate
            ((X509Certificate) cert).checkValidity();
        }

        Log.debug("Using Key '%s' for signing", keyAlias);

        PDSignature signature = new PDSignature();
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
        signature.setSignDate(Calendar.getInstance());

        // Try to take over some metadata:
        String commonName = getSubject(cert, "CN");
        if (commonName != null)
            signature.setName(commonName);

        StringBuilder sb = new StringBuilder(100);
        // Locality (city), State and Country
        for (String name : new String[]{"L", "S", "C"}) {
            String value = getSubject(cert, name);
            if (value != null) {
                if (!sb.isEmpty())
                    sb.append(", ");
                sb.append(value);
            }
        }
        signature.setLocation(sb.toString());

        SignatureOptions signatureOptions = new SignatureOptions();
        // register signature dictionary and sign interface
        document.addSignature(signature, this, signatureOptions);
    }

    /**
     * Extracts a value from X509Certificate's Subject DN.
     */
    public static String getSubject(Certificate certificate, String name) throws InvalidNameException {
        StringBuilder sb = new StringBuilder();

        if (certificate instanceof X509Certificate x509) {
            // 1. Get the X500Principal for the subject
            X500Principal principal = x509.getSubjectX500Principal();

            // 2. Get the Subject DN string
            String dn = principal.getName();

            // 3. Parse the DN string using LdapName
            LdapName ldapDN = new LdapName(dn);


            // 4. Iterate through the Relative Distinguished Names (RDNs)
            for (Rdn rdn : ldapDN.getRdns()) {
                // Check if the attribute type matchs
                if (rdn.getType().equalsIgnoreCase(name)) {
                    // add the value associated
                    if (!sb.isEmpty())
                        sb.append(", ");
                    sb.append(rdn.getValue().toString());
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }


}