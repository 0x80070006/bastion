// SPDX-License-Identifier: GPL-3.0-or-later
// Default locale. Its keys define the Messages type every other locale must satisfy.
export const fr = {
  "app.status.noDevice": "Aucun téléphone appairé",
  "app.status.noDeviceHint":
    "Ajoutez un téléphone pour voir sa position et pouvoir le faire sonner ou le verrouiller.",
  "app.action.addPhone": "Ajouter un téléphone",
  "app.action.addPhoneUnavailable": "Disponible après la configuration du relay.",
  "app.footer.protocol": "Protocole v{version}",
  "nav.dashboard": "Tableau de bord",
  "nav.journal": "Journal",
  "nav.settings": "Réglages",
} as const;

export type MessageKey = keyof typeof fr;
export type Messages = Record<MessageKey, string>;
