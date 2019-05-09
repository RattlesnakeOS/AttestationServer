package app.attestation.server;

import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;

import java.io.File;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import java.util.Properties;

import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.google.common.io.BaseEncoding;

import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.AmazonSNS;

class AlertDispatcher implements Runnable {
    private static final long WAIT_MS = 15 * 60 * 1000;
    private static final int TIMEOUT_MS = 30 * 1000;

    @Override
    public void run() {
        final SQLiteConnection conn = new SQLiteConnection(AttestationProtocol.ATTESTATION_DATABASE);
        final SQLiteStatement selectAccounts;
        final SQLiteStatement selectExpired;
        final SQLiteStatement selectFailed;
        final SQLiteStatement selectEmails;
        try {
            AttestationServer.open(conn, false);
            selectAccounts = conn.prepare("SELECT userId, alertDelay FROM Accounts");
            selectExpired = conn.prepare("SELECT fingerprint FROM Devices " +
                    "WHERE userId = ? AND verifiedTimeLast < ? AND deletionTime IS NULL");
            selectFailed = conn.prepare("SELECT fingerprint FROM Devices " +
                    "WHERE userId = ? AND failureTimeLast IS NOT NULL AND deletionTime IS NULL");
            selectEmails = conn.prepare("SELECT address FROM EmailAddresses WHERE userId = ?");
        } catch (final SQLiteException e) {
            conn.dispose();
            throw new RuntimeException(e);
        }

        while (true) {
            try {
                Thread.sleep(WAIT_MS);
            } catch (final InterruptedException e) {
                return;
            }

            System.err.println("dispatching alerts");

            final String arn = System.getenv("SNS_ARN");
            final String region = System.getenv("REGION");

            AmazonSNS snsClient = null;
            try {
                InstanceProfileCredentialsProvider provider = new InstanceProfileCredentialsProvider(true);
                snsClient = AmazonSNSClientBuilder.standard().withCredentials(provider).withRegion(region).build();
            } catch (Exception e) {
                System.err.println("failed to send to SNS");
                e.printStackTrace();
                continue;
            }

            try {
                while (selectAccounts.step()) {
                    final long userId = selectAccounts.columnLong(0);
                    final int alertDelay = selectAccounts.columnInt(1);

                    final StringBuilder expired = new StringBuilder();
                    selectExpired.bind(1, userId);
                    selectExpired.bind(2, System.currentTimeMillis() - alertDelay * 1000);
                    while (selectExpired.step()) {
                        final byte[] fingerprint = selectExpired.columnBlob(0);
                        final String encoded = BaseEncoding.base16().encode(fingerprint);
                        expired.append("* ").append(encoded).append("\n");
                    }
                    selectExpired.reset();

                    if (expired.length() > 0) {
                        selectEmails.bind(1, userId);
                        while (selectEmails.step()) {
                            final String address = selectEmails.columnString(0);
                            System.err.println("sending email to " + address);
                            snsClient.publish(arn,
                                    "The following devices have failed to provide valid attestations before the expiry time:\n\n" +
                                            expired.toString(),
                                    "Devices failed to provide valid attestations within " +
                                            alertDelay / 60 / 60 + " hours");
                        }
                        selectEmails.reset();
                    }

                    final StringBuilder failed = new StringBuilder();
                    selectFailed.bind(1, userId);
                    while (selectFailed.step()) {
                        final byte[] fingerprint = selectFailed.columnBlob(0);
                        final String encoded = BaseEncoding.base16().encode(fingerprint);
                        failed.append("* ").append(encoded).append("\n");
                    }
                    selectFailed.reset();

                    if (failed.length() > 0) {
                        selectEmails.bind(1, userId);
                        while (selectEmails.step()) {
                            final String address = selectEmails.columnString(0);
                            System.err.println("sending email to " + address);
                            snsClient.publish(arn,
                                    "Devices provided invalid attestations",
                                    "The following devices have provided invalid attestations:\n\n" +
                                            failed.toString());
                        }
                        selectEmails.reset();
                    }
                }
            } catch (final SQLiteException e) {
                e.printStackTrace();
            } finally {
                try {
                    selectAccounts.reset();
                    selectExpired.reset();
                    selectFailed.reset();
                    selectEmails.reset();
                } catch (final SQLiteException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
