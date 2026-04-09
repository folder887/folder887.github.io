import crypto from 'node:crypto';

export const config = { runtime: 'nodejs18.x' };

export default async function handler(req, res) {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  const shopId    = process.env.YOOKASSA_SHOP_ID;
  const secretKey = process.env.YOOKASSA_SECRET_KEY;

  if (!shopId || !secretKey) {
    return res.status(500).json({ error: 'Payment provider not configured' });
  }

  const { amountKopecks, description, returnUrl } = req.body || {};

  if (!amountKopecks || amountKopecks < 100) {
    return res.status(400).json({ error: 'Invalid amount' });
  }

  const amountRub = (amountKopecks / 100).toFixed(2);
  const idempotenceKey = crypto.randomUUID();

  const body = {
    amount: { value: amountRub, currency: 'RUB' },
    payment_method_data: { type: 'sberbank' },
    confirmation: {
      type: 'redirect',
      return_url: returnUrl || 'https://folder887.vercel.app/?payment=success',
    },
    description: description || 'Заказ FOLDER',
    capture: true,
  };

  const response = await fetch('https://api.yookassa.ru/v3/payments', {
    method: 'POST',
    headers: {
      'Authorization': 'Basic ' + Buffer.from(`${shopId}:${secretKey}`).toString('base64'),
      'Content-Type': 'application/json',
      'Idempotence-Key': idempotenceKey,
    },
    body: JSON.stringify(body),
  });

  const data = await response.json();

  if (!response.ok) {
    console.error('YooKassa error:', data);
    return res.status(400).json({ error: data.description || 'Payment creation failed' });
  }

  return res.status(200).json({
    paymentId: data.id,
    confirmationUrl: data.confirmation.confirmation_url,
  });
}
