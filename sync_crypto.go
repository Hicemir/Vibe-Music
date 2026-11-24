package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
)

type SyncIdentity struct {
	Priv       *ecdsa.PrivateKey
	PubSpkiB64 string
	DeviceID   string
}

func deviceIDFromSpki(spki []byte) string {
	sum := sha256.Sum256(spki)
	return hex.EncodeToString(sum[:16])
}

func loadOrCreateSyncIdentity(s *Store) (*SyncIdentity, error) {
	if raw := s.GetSyncSetting("sync_identity_pkcs8"); raw != "" {
		if der, err := base64.StdEncoding.DecodeString(raw); err == nil {
			if kAny, err := x509.ParsePKCS8PrivateKey(der); err == nil {
				if priv, ok := kAny.(*ecdsa.PrivateKey); ok && priv.Curve == elliptic.P256() {
					spki, err := x509.MarshalPKIXPublicKey(&priv.PublicKey)
					if err == nil {
						return &SyncIdentity{
							Priv:       priv,
							PubSpkiB64: base64.StdEncoding.EncodeToString(spki),
							DeviceID:   deviceIDFromSpki(spki),
						}, nil
					}
				}
			}
		}
	}
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, err
	}
	spki, err := x509.MarshalPKIXPublicKey(&priv.PublicKey)
	if err != nil {
		return nil, err
	}
	if err := s.SetSyncSetting("sync_identity_pkcs8", base64.StdEncoding.EncodeToString(pkcs8)); err != nil {
		return nil, err
	}
	return &SyncIdentity{
		Priv:       priv,
		PubSpkiB64: base64.StdEncoding.EncodeToString(spki),
		DeviceID:   deviceIDFromSpki(spki),
	}, nil
}

func parseP256Spki(spkiB64 string) (*ecdsa.PublicKey, []byte, error) {
	der, err := base64.StdEncoding.DecodeString(spkiB64)
	if err != nil {
		return nil, nil, err
	}
	pubAny, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, nil, err
	}
	ecPub, ok := pubAny.(*ecdsa.PublicKey)
	if !ok || ecPub.Curve != elliptic.P256() {
		return nil, nil, errors.New("expected P-256 ECDSA public key")
	}
	return ecPub, der, nil
}

func verifyDeviceIDAndKey(deviceID, spkiB64 string) (*ecdsa.PublicKey, error) {
	pub, der, err := parseP256Spki(spkiB64)
	if err != nil {
		return nil, err
	}
	if deviceIDFromSpki(der) != deviceID {
		return nil, errors.New("deviceId does not match public key")
	}
	return pub, nil
}

func signMessage(priv *ecdsa.PrivateKey, msg []byte) (string, error) {
	h := sha256.Sum256(msg)
	sig, err := ecdsa.SignASN1(rand.Reader, priv, h[:])
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(sig), nil
}

func verifySignature(pub *ecdsa.PublicKey, msg []byte, sigB64 string) bool {
	sig, err := base64.StdEncoding.DecodeString(sigB64)
	if err != nil {
		return false
	}
	h := sha256.Sum256(msg)
	return ecdsa.VerifyASN1(pub, h[:], sig)
}

func generateEphemeralKey() (*ecdsa.PrivateKey, string, error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, "", err
	}
	spki, err := x509.MarshalPKIXPublicKey(&priv.PublicKey)
	if err != nil {
		return nil, "", err
	}
	return priv, base64.StdEncoding.EncodeToString(spki), nil
}

func computeECDHSharedSecret(priv *ecdsa.PrivateKey, peerEphSpkiB64 string) ([]byte, error) {
	localECDH, err := priv.ECDH()
	if err != nil {
		return nil, err
	}
	peerPub, _, err := parseP256Spki(peerEphSpkiB64)
	if err != nil {
		return nil, err
	}
	peerECDH, err := peerPub.ECDH()
	if err != nil {
		return nil, err
	}
	return localECDH.ECDH(peerECDH)
}

func randomHex(numBytes int) string {
	b := make([]byte, numBytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func deriveSessionKeys(sharedSecret []byte, initNonceHex, respNonceHex string) (sessionKey, authKey []byte, err error) {
	n1, err := hex.DecodeString(initNonceHex)
	if err != nil || len(n1) == 0 {
		return nil, nil, errors.New("invalid init nonce")
	}
	n2, err := hex.DecodeString(respNonceHex)
	if err != nil || len(n2) == 0 {
		return nil, nil, errors.New("invalid resp nonce")
	}
	salt := make([]byte, 0, len(n1)+len(n2))
	salt = append(salt, n1...)
	salt = append(salt, n2...)

	macExtract := hmac.New(sha256.New, salt)
	macExtract.Write(sharedSecret)
	prk := macExtract.Sum(nil)

	macT1 := hmac.New(sha256.New, prk)
	macT1.Write([]byte("vibemusic-sync-enc-v1"))
	macT1.Write([]byte{0x01})
	sessionKey = macT1.Sum(nil)

	macT2 := hmac.New(sha256.New, prk)
	macT2.Write(sessionKey)
	macT2.Write([]byte("vibemusic-sync-mac-v1"))
	macT2.Write([]byte{0x02})
	authKey = macT2.Sum(nil)

	return sessionKey, authKey, nil
}

func buildSyncTranscript(initID, respID, initPub, respPub, initEph, respEph, initNonce, respNonce string) string {
	return "vibemusic-pair-v1|" + initID + "|" + respID + "|" + initPub + "|" + respPub + "|" + initEph + "|" + respEph + "|" + initNonce + "|" + respNonce
}

func computeSasPin(transcript string) string {
	sum := sha256.Sum256([]byte("vibemusic-sas-v1|" + transcript))
	v := (uint32(sum[0]&0x7F) << 24) | (uint32(sum[1]) << 16) | (uint32(sum[2]) << 8) | uint32(sum[3])
	return fmt.Sprintf("%06d", v%1000000)
}

func computeRequestMAC(authKey []byte, method, pathWithQuery, sessionID, nonceHeader string) string {
	m := hmac.New(sha256.New, authKey)
	m.Write([]byte(method))
	m.Write([]byte("|"))
	m.Write([]byte(pathWithQuery))
	m.Write([]byte("|"))
	m.Write([]byte(sessionID))
	m.Write([]byte("|"))
	m.Write([]byte(nonceHeader))
	return hex.EncodeToString(m.Sum(nil))
}

func verifyRequestMAC(authKey []byte, method, pathWithQuery, sessionID, nonceHeader, providedHex string) bool {
	expected := computeRequestMAC(authKey, method, pathWithQuery, sessionID, nonceHeader)
	return len(expected) == len(providedHex) && subtle.ConstantTimeCompare([]byte(expected), []byte(providedHex)) == 1
}

func newGCM(key []byte) (cipher.AEAD, error) {
	blk, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(blk)
}

func encryptSingleBuffer(sessionKey, plaintext []byte) ([]byte, error) {
	gcm, err := newGCM(sessionKey)
	if err != nil {
		return nil, err
	}
	iv := make([]byte, 12)
	if _, err := rand.Read(iv); err != nil {
		return nil, err
	}
	return gcm.Seal(iv, iv, plaintext, nil), nil
}

func decryptSingleBuffer(sessionKey, sealed []byte) ([]byte, error) {
	if len(sealed) < 12+16 {
		return nil, errors.New("ciphertext too short")
	}
	gcm, err := newGCM(sessionKey)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, sealed[:12], sealed[12:], nil)
}

func deriveChunkNonce(baseIV []byte, idx uint64) [12]byte {
	var nonce [12]byte
	copy(nonce[:], baseIV[:12])
	v := binary.BigEndian.Uint64(nonce[4:12]) ^ idx
	binary.BigEndian.PutUint64(nonce[4:12], v)
	return nonce
}

func makeChunkAAD(idx uint64, eof byte) [9]byte {
	var aad [9]byte
	binary.BigEndian.PutUint64(aad[:8], idx)
	aad[8] = eof
	return aad
}

func encryptedStreamSize(plainSize int64) int64 {
	if plainSize < 0 {
		return -1
	}
	fullChunks := plainSize / syncChunkSize
	rem := plainSize % syncChunkSize
	total := int64(12) + fullChunks*(4+syncChunkSize+16)
	if rem > 0 {
		total += 4 + rem + 16
	}
	total += 4 + 16 // mandatory EOF chunk
	return total
}

func writeEncryptedStream(w io.Writer, sessionKey []byte, r io.Reader, onBytes func(int64)) error {
	gcm, err := newGCM(sessionKey)
	if err != nil {
		return err
	}
	baseIV := make([]byte, 12)
	if _, err := rand.Read(baseIV); err != nil {
		return err
	}
	if _, err := w.Write(baseIV); err != nil {
		return err
	}
	buf := make([]byte, syncChunkSize)
	var lenBuf [4]byte
	var idx uint64
	for {
		n, rerr := io.ReadFull(r, buf)
		if n > 0 {
			nonce := deriveChunkNonce(baseIV, idx)
			aad := makeChunkAAD(idx, 0x00)
			ct := gcm.Seal(nil, nonce[:], buf[:n], aad[:])
			binary.BigEndian.PutUint32(lenBuf[:], uint32(n))
			if _, err := w.Write(lenBuf[:]); err != nil {
				return err
			}
			if _, err := w.Write(ct); err != nil {
				return err
			}
			idx++
			if onBytes != nil {
				onBytes(int64(n))
			}
		}
		if rerr == io.EOF || rerr == io.ErrUnexpectedEOF {
			break
		}
		if rerr != nil {
			return rerr
		}
	}
	// Mandatory authenticated EOF marker prevents silent truncation
	nonce := deriveChunkNonce(baseIV, idx)
	aad := makeChunkAAD(idx, 0x01)
	eofTag := gcm.Seal(nil, nonce[:], nil, aad[:])
	binary.BigEndian.PutUint32(lenBuf[:], 0)
	if _, err := w.Write(lenBuf[:]); err != nil {
		return err
	}
	_, err = w.Write(eofTag)
	return err
}

func readEncryptedStream(r io.Reader, sessionKey []byte, w io.Writer, expectedPlainSize int64, onBytes func(int64)) (int64, error) {
	gcm, err := newGCM(sessionKey)
	if err != nil {
		return 0, err
	}
	baseIV := make([]byte, 12)
	if _, err := io.ReadFull(r, baseIV); err != nil {
		return 0, fmt.Errorf("read stream iv: %w", err)
	}
	var lenBuf [4]byte
	var idx uint64
	var total int64
	ctBuf := make([]byte, syncChunkSize+16)
	for {
		if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
			return total, fmt.Errorf("read chunk len: %w", err)
		}
		plainLen := binary.BigEndian.Uint32(lenBuf[:])
		if plainLen == 0 {
			var eofTag [16]byte
			if _, err := io.ReadFull(r, eofTag[:]); err != nil {
				return total, fmt.Errorf("read eof tag: %w", err)
			}
			nonce := deriveChunkNonce(baseIV, idx)
			aad := makeChunkAAD(idx, 0x01)
			if _, err := gcm.Open(nil, nonce[:], eofTag[:], aad[:]); err != nil {
				return total, fmt.Errorf("eof auth failed: %w", err)
			}
			if expectedPlainSize >= 0 && total != expectedPlainSize {
				return total, fmt.Errorf("size mismatch: got %d want %d", total, expectedPlainSize)
			}
			return total, nil
		}
		if plainLen > syncChunkSize {
			return total, fmt.Errorf("chunk too large: %d", plainLen)
		}
		need := int(plainLen) + 16
		if _, err := io.ReadFull(r, ctBuf[:need]); err != nil {
			return total, fmt.Errorf("read chunk body: %w", err)
		}
		nonce := deriveChunkNonce(baseIV, idx)
		aad := makeChunkAAD(idx, 0x00)
		plain, err := gcm.Open(nil, nonce[:], ctBuf[:need], aad[:])
		if err != nil {
			return total, fmt.Errorf("chunk %d decrypt failed: %w", idx, err)
		}
		if _, err := w.Write(plain); err != nil {
			return total, err
		}
		idx++
		total += int64(len(plain))
		if onBytes != nil {
			onBytes(int64(len(plain)))
		}
	}
}
