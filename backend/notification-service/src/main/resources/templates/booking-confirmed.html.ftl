<!DOCTYPE html>
<html>
<head>
    <title>Booking Confirmed</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
        .header { background-color: #10b981; color: white; padding: 15px; text-align: center; border-radius: 5px 5px 0 0; }
        .content { padding: 20px; }
        .details { background-color: #f3f4f6; padding: 15px; border-radius: 5px; margin: 15px 0; }
        .footer { text-align: center; padding: 10px; font-size: 0.8em; color: #777; border-top: 1px solid #ddd; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h2>Your Tickets Are Confirmed!</h2>
        </div>
        <div class="content">
            <p>Hi,</p>
            <p>Great news! Your booking has been successfully processed and confirmed.</p>
            
            <div class="details">
                <h3>Booking Details:</h3>
                <p><strong>Booking ID:</strong> ${bookingId}</p>
                <p><strong>Showtime ID:</strong> ${showtimeId}</p>
                <p><strong>Seats:</strong> ${seatCodes?join(", ")}</p>
                <p><strong>Total Amount:</strong> ${amount} ${currency}</p>
            </div>
            
            <p>Please present this confirmation email or Booking ID at the venue gate to enter.</p>
            <p>Enjoy the match!</p>
        </div>
        <div class="footer">
            &copy; 2026 Stadium Ticketing Platform. All rights reserved.
        </div>
    </div>
</body>
</html>
