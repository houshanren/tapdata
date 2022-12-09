package com.tapdata.tm.sdk.auth;

import com.tapdata.tm.sdk.util.Base64Util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * @author lg<lirufei0808 @ gmail.com>
 * @date 2020/9/9 6:39 上午
 * @description
 */
public class HmacSHA1Signer extends Signer{
	public static final String ENCODING = "UTF-8";
	private static final String ALGORITHM_NAME = "HmacSHA1";

	@Override
	public String signString(String stringToSign, String accessKeySecret) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM_NAME);
			mac.init(new SecretKeySpec(accessKeySecret.getBytes(ENCODING), ALGORITHM_NAME));
			byte[] signData = mac.doFinal(stringToSign.getBytes(ENCODING));
			return Base64Util.encode(signData);
		} catch (NoSuchAlgorithmException | InvalidKeyException | UnsupportedEncodingException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	@Override
	public String signString(String stringToSign, Credentials credentials) {
		return signString(stringToSign, credentials.getAccessKeySecret());
	}

	@Override
	public String getSignerName() {
		return ALGORITHM_NAME;
	}

	@Override
	public String getSignerVersion() {
		return "1.0";
	}

	@Override
	public String getSignerType() {
		return null;
	}
}
