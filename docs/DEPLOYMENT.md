# Deployment Guide

This guide describes the step-by-step instructions for deploying ScaleKit's Spring Boot backend to Render, its React dashboard to Vercel, and configuring monitoring with UptimeRobot.

## Prerequisites
- GitHub account
- Render account ([render.com](https://render.com))
- Vercel account ([vercel.com](https://vercel.com))
- Supabase project (managed PostgreSQL database)
- Upstash Redis (serverless Redis instance)

---

## Step 1: Push to GitHub

If not already initialized as a repository:
```bash
git init
git add .
git commit -m "feat: ScaleKit complete - 30 steps"
git remote add origin https://github.com/{username}/ScaleKit.git
git push -u origin main
```

---

## Step 2: Deploy Backend to Render

1. Log in to [render.com](https://render.com) and click **New** → **Web Service**.
2. Connect your GitHub account and select the **ScaleKit** repository.
3. Configure the service settings:
   - **Name:** `scalekit-api`
   - **Runtime:** `Docker`
   - **Dockerfile Path:** `./Dockerfile`
   - **Region:** `Singapore` (or region closest to your DB/Redis host)
   - **Plan:** `Free`
4. Add the following **Environment Variables**:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `POSTGRES_URL=jdbc:postgresql://<supabase_host>:5432/postgres` (replace with your Supabase JDBC connection URL)
   - `POSTGRES_USER=postgres.<your_project_id>`
   - `POSTGRES_PASSWORD=<your_supabase_password>`
   - `REDIS_HOST=<your_upstash_redis_endpoint>`
   - `REDIS_PORT=6379` (or the Upstash port)
   - `REDIS_PASSWORD=<your_upstash_password>`
   - `REDIS_SSL=true`
   - `APP_BASE_URL=https://scalekit-api.onrender.com`
   - `APP_SECURITY_ADMIN_API_KEY=<your_generated_random_admin_key>`
5. Click **Deploy Web Service**.

---

## Step 3: Deploy Frontend to Vercel

1. Log in to [vercel.com](https://vercel.com) and click **Add New** → **Project**.
2. Import the **ScaleKit** repository from your GitHub account.
3. Configure the build settings:
   - **Framework Preset:** `Vite`
   - **Root Directory:** `scalekit-frontend`
   - **Build Command:** `npm run build`
   - **Output Directory:** `dist`
4. Add the following **Environment Variable**:
   - `VITE_API_URL=https://scalekit-api.onrender.com`
5. Click **Deploy**.

---

## Step 4: Keep Service Active via UptimeRobot

Render's free tier spins down containers after 15 minutes of inactivity, causing a 30s cold-start delay for the first subsequent visitor. To prevent this:
1. Log in to [uptimerobot.com](https://uptimerobot.com).
2. Click **Add New Monitor**.
3. Select Monitor Type: **HTTPS**.
4. Configure the settings:
   - **Friendly Name:** `ScaleKit Live Actuator`
   - **URL (or IP):** `https://scalekit-api.onrender.com/actuator/health`
   - **Monitoring Interval:** `Every 14 minutes` (keeps container hot)
5. Save the monitor.

---

## Step 5: Update README Placeholders

Once both Render and Vercel services are deployed:
1. Replace all `{username}` placeholders in `README.md` and docs with your actual GitHub username.
2. Replace Vercel/Render urls with your custom URLs if you configured custom domains.
3. Commit and push the final changes to GitHub.
