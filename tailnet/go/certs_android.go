package libtailnet

import "os"

// Since Android 14 the CA roots are updated through the Conscrypt APEX and
// /system/etc/security/cacerts (which Go reads) is no longer kept current.
// Point Go at the APEX store when it exists. This must run before
// crypto/x509 loads the roots, which it does on first use. As with the
// variables that Start sets, the app creates SSL_CERT_DIR before it starts
// other threads.
func init() {
	const apexCerts = "/apex/com.android.conscrypt/cacerts"
	if os.Getenv("SSL_CERT_DIR") != "" || os.Getenv("SSL_CERT_FILE") != "" {
		return
	}
	if fi, err := os.Stat(apexCerts); err == nil && fi.IsDir() {
		os.Setenv("SSL_CERT_DIR", apexCerts)
	}
}
