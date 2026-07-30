<!DOCTYPE html>
<html>
<head>
    <title>Your account is now active</title>
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
            <h2>Your account is active!</h2>
        </div>
        <div class="content">
            <p>Hi there,</p>
            <p>Your account (ID: <strong>${accountId}</strong>, email: <strong>${email}</strong>) has been
            successfully verified and is now active. You can sign in and start booking tickets right away.</p>
            <p>If you did not expect this email, please contact our support team.</p>
        </div>
        <div class="footer">
            &copy; 2026 Stadium Ticketing Platform. All rights reserved.
        </div>
    </div>
</body>
</html>
