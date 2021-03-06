package com.lts.core.commons.file;

import java.io.IOException;

/**
 * Robert HG (254963746@qq.com) on 5/27/15.
 */
public class FileLockTest {

    public static void main(String[] args) throws IOException {

        final FileLock lock = new FileLock("/Users/hugui/Documents/test/__db.lock");

        for (int i = 0; i < 50; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            lock.tryLock();
                        } catch (Throwable e) {
                            e.printStackTrace();
                        } finally {
                            lock.release();
                        }
                    }

                }
            }).start();
        }

        System.in.read();
    }


}