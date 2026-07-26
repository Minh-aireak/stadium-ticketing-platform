<!DOCTYPE html>
<html>
<head>
    <title>Booking Cancelled</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
        .header { background-color: #ef4444; color: white; padding: 15px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { padding: 20px; }
        .details { background-color: #f3f4f6; padding: 15px; border-radius: 5px; margin: 15px 0; }
        .footer { text-align: center; padding: 10px; font-size: 0.8em; color: #777; border-top: 1px solid #ddd; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>Booking Cancellation Notification</h2>
        </div>
        <div class="content">
            <p>Hi,</p>
            <p>We regret to inform you that your booking has been cancelled.</p>
            
            <div class="details">
                <h3>Cancellation Details:</h3>
                <p><strong>Booking ID:</strong> ${bookingId}</p>
                <p><strong>Showtime ID:</strong> ${showtimeId}</p>
                <p><strong>Reason:</strong> ${reason}</p>
            </div>
            
            <p>If a payment was already charged, it will be automatically refunded within 3-5 business days.</p>
            <p>If you have any questions, please contact our support team.</p>
        </div>
        <div class="footer">
            &copy; 2026 Stadium Ticketing Platform. All rights reserved.
        </div>
    </div>
</body>
</html>
