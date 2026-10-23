package com.gathin.tvhome;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The extra trust anchor (res/xml/network_security_config.xml) is the genuine ISRG Root X2 and still valid. */
public class TrustAnchorTest {

    @Test
    public void bundledRootIsIsrgRootX2() throws Exception {
        X509Certificate cert;
        try (InputStream in = new FileInputStream("src/main/res/raw/isrg_root_x2.pem")) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        cert.checkValidity();
        cert.verify(cert.getPublicKey());
        assertTrue(cert.getSubjectX500Principal().getName().contains("CN=ISRG Root X2"));
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())) hex.append(String.format("%02X", b));
        assertEquals("69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470", hex.toString());
    }
}
