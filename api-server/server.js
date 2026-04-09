import crypto from 'node:crypto';
import express from 'express';

const app = express();
app.use(express.json());

// Allow requests from GitHub Pages and local dev
const ALLOWED_ORIGINS = [
  /^https:\/\/[\w-]+\.github\.io$/,
  /^http:\/\/localhost(:\d+)?$/,
  /^http:\/\/127\.0\.0\.1(:\d+)?$/,
];

app.use((req, res, next) => {
  const origin = req.headers.origin || '';
  if (ALLOWED_ORIGINS.some(re => re.test(origin))) {
    res.setHeader('Access-Control-Allow-Origin', origin);
  }
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.sendStatus(200);
  next();
});

app.post('/create-payment', async (req, res) => {
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

  const response = await fetch('https://api.yookassa.ru/v3/payments', {
    method: 'POST',
    headers: {
      'Authorization': 'Basic ' + Buffer.from(`${shopId}:${secretKey}`).toString('base64'),
      'Content-Type': 'application/json',
      'Idempotence-Key': crypto.randomUUID(),
    },
    body: JSON.stringify({
      amount: { value: amountRub, currency: 'RUB' },
      payment_method_data: { type: 'sberbank' },
      confirmation: {
        type: 'redirect',
        return_url: returnUrl || 'https://folder887.github.io/?payment=success',
      },
      description: (description || 'Заказ FOLDER').slice(0, 128),
      capture: true,
    }),
  });

  const data = await response.json();

  if (!response.ok) {
    console.error('YooKassa error:', data);
    return res.status(400).json({ error: data.description || 'Payment creation failed' });
  }

  return res.json({
    paymentId: data.id,
    confirmationUrl: data.confirmation.confirmation_url,
  });
});

app.get('/health', (_, res) => res.json({ ok: true }));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`API server running on port ${PORT}`));
