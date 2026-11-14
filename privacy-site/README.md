# Privacy policy site (IIS)

A static site: `index.html`, `style.css`, `web.config`. No server code.

## Before publishing
In `index.html`, replace the three placeholders everywhere they appear (search for the square brackets):
- `[APP NAME]` the name you pick for the app
- `[DEVELOPER NAME]` your name or business name
- `[CONTACT EMAIL]` a mailbox you will actually read (Google Play requires a way to contact you)

Also read it through. It describes what the app does today (see the app's code), but I am not a lawyer; if the app changes, or you want legal certainty, have it checked.

## Put it on your Windows server
1. Copy this folder to the server, e.g. `C:\inetpub\privacy-site`.
2. IIS Manager, right-click Sites, Add Website. Site name `privacy`, physical path the folder above, a host name such as `privacy.yourdomain.com`, port 80 for now.
3. Give the site's application pool identity (or `IIS_IUSRS`) read access to the folder if IIS asks.
4. Point DNS for the host name at the server (an A record), and open ports 80 and 443 in Windows Firewall and your router.
5. Add HTTPS: easiest is the free win-acme client (`wacs.exe`), which gets a Let's Encrypt certificate and binds it. Google Play wants an https:// link.
6. Visit `https://privacy.yourdomain.com` and check it loads. That address is what you give Google Play as the Privacy Policy URL.

The `web.config` only uses built-in IIS features (default document, security headers, a 404 page), so no extra modules are needed.
