package com.jasonlat.ai.types.utils;

/**
 * @author jasonlat
 */
public class RandomCodeUtil {

        /**
        * 生成随机验证码
        *
        * @param length 验证码长度
        * @return 验证码
        */
        public static String generateRandomCode(int length) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < length; i++) {
                code.append((int) (Math.random() * 10));
            }
            return code.toString();
        }

        // 同时包含数字字母
        public static String generateRandomCode(int length, boolean includeLetter) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < length; i++) {
                if (includeLetter) {
                    if (Math.random() > 0.5) {
                        code.append((char) (Math.random() * 26 + 'a'));
                    } else {
                        code.append((int) (Math.random() * 10));
                    }
                } else {
                    code.append((int) (Math.random() * 10));
                }
            }
            return code.toString();
        }

    public static void main(String[] args) {
        System.out.println(generateRandomCode(6));
        System.out.println(generateRandomCode(6, true));
    }
}