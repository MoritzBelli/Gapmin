import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'de.moritzbelli.gapmin',
  appName: 'Gapmin',
  webDir: 'dist',
  // http scheme so the app may call the NAS over plain HTTP in the LAN / WireGuard
  server: { androidScheme: 'http' },
  backgroundColor: '#060708',
  android: { allowMixedContent: true },
  plugins: {
    StatusBar: { overlaysWebView: false, style: 'DARK', backgroundColor: '#060708' },
  },
}

export default config
