<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8"/>
    <title>Reset your password</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
        .header { background-color: #b45309; color: white; padding: 15px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { padding: 20px; }
        .notice { background-color: #fef3c7; padding: 12px 15px; border-radius: 5px; margin: 15px 0; }
        .footer { text-align: center; padding: 10px; font-size: 0.8em; color: #777; border-top: 1px solid #ddd; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>Reset Your Password</h2>
        </div>
        <div class="content">
            <p>Hi ${customerName},</p>
            <p>We received a request to reset the password for your Stadium Ticketing account.
               Click the button below to choose a new one:</p>
            <p style="text-align: center;">
                <a href="${resetUrl}" style="display: inline-block; padding: 12px 24px; background-color: #b45309; color: white; text-decoration: none; border-radius: 4px;">Reset my password</a>
            </p>
            <p>Or copy and paste this link into your browser: <br/>${resetUrl}</p>
            <div class="notice">
                This link expires in <strong>${expiresInMinutes} minutes</strong> and can be used only once.
            </div>
            <p>If you did not request a password reset, ignore this email — your password stays
               unchanged and no action is needed.</p>
        </div>
        <div class="footer">
            &copy; 2026 Stadium Ticketing Platform. All rights reserved.
        </div>
    </div>
</body>
</html>
