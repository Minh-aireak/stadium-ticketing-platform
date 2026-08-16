<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8"/>
    <title>Payment received</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
        .header { background-color: #10b981; color: white; padding: 15px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { padding: 20px; }
        .details { background-color: #f3f4f6; padding: 15px; border-radius: 5px; margin: 15px 0; }
        .details td { padding: 4px 0; }
        .details td.label { color: #555; padding-right: 16px; }
        .footer { text-align: center; padding: 10px; font-size: 0.8em; color: #777; border-top: 1px solid #ddd; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>Payment Successful</h2>
        </div>
        <div class="content">
            <p>Hi ${customerName},</p>
            <p>We have received your payment. Your tickets are being confirmed right now.</p>

            <div class="details">
                <table>
                    <tr><td class="label">Customer</td><td><strong>${customerName}</strong></td></tr>
                    <tr><td class="label">Order ID</td><td><strong>${orderId}</strong></td></tr>
                    <tr><td class="label">Amount paid</td><td><strong>${amount}</strong></td></tr>
                    <tr><td class="label">Paid at</td><td><strong>${paidAt}</strong></td></tr>
                </table>
            </div>

            <p>Keep this email as your payment receipt. You can view the booking any time from
               the "My tickets" screen.</p>
            <p>Enjoy the match!</p>
        </div>
        <div class="footer">
            &copy; 2026 Stadium Ticketing Platform. All rights reserved.
        </div>
    </div>
</body>
</html>
