<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8"/>
    <title>Refund issued</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
        .header { background-color: #0ea5e9; color: white; padding: 15px; text-align: center; border-radius: 5px 5px 0 0; }
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
            <h2>Refund Issued</h2>
        </div>
        <div class="content">
            <p>Hi ${customerName},</p>
            <p>We have refunded your payment in full. Nothing further is needed from you.</p>

            <div class="details">
                <table>
                    <tr><td class="label">Order ID</td><td><strong>${orderId}</strong></td></tr>
                    <tr><td class="label">Amount refunded</td><td><strong>${amount}</strong></td></tr>
                    <tr><td class="label">Reason</td><td><strong>${reason!"Booking cancelled"}</strong></td></tr>
                    <tr><td class="label">Issued at</td><td><strong>${refundedAt}</strong></td></tr>
                </table>
            </div>

            <p>The money goes back to the card you paid with. Your bank decides how long that
               takes to appear on your statement &mdash; usually five to ten working days.</p>
            <p>If it has not arrived after that, reply to this email with the order ID above.</p>
        </div>
        <div class="footer">
            &copy; 2026 Stadium Ticketing Platform. All rights reserved.
        </div>
    </div>
</body>
</html>
