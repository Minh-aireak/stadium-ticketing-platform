<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8"/>
    <title>Welcome to Stadium Ticketing Platform</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
        .header { background-color: #1e3a8a; color: white; padding: 15px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { padding: 20px; }
        .footer { text-align: center; padding: 10px; font-size: 0.8em; color: #777; border-top: 1px solid #ddd; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>Welcome to Stadium Ticketing!</h2>
        </div>
        <div class="content">
            <p>Hi ${customerName},</p>
            <p>Thank you for registering on our platform. Your account (ID: <strong>${accountId}</strong>) is currently pending verification.</p>
            <p>Please confirm <strong>${email}</strong> is your email address by clicking the button below to activate your account:</p>
            <p style="text-align: center;">
                <a href="${verificationUrl}" style="display: inline-block; padding: 12px 24px; background-color: #1e3a8a; color: white; text-decoration: none; border-radius: 4px;">Verify my email</a>
            </p>
            <p>Or copy and paste this link into your browser: <br/>${verificationUrl}</p>
            <p>If you did not make this request, you can safely ignore this email.</p>
        </div>
        <div class="footer">
            &copy; 2026 Stadium Ticketing Platform. All rights reserved.
        </div>
    </div>
</body>
</html>
