# Horéveil ⏰

Réveil Android natif à planning intelligent pour horaires décalés, 3×8, congés et siestes.

> **Version 0.1.0 — prototype, non encore validé sur un téléphone réel.**
> Ne remplacez pas votre réveil habituel avant d'avoir vérifié les permissions, le son, la veille et les alarmes après redémarrage.

## Fonctionnalités incluses

- Réveils de travail automatiquement calculés à partir des postes **matin / après-midi / nuit** et de la marge de préparation.
- Planning affecté semaine par semaine, ou par blocs de **6 ou 12 semaines**, sans imposer de rotation fixe.
- Modification exceptionnelle d'une journée (poste, début, fin, marge), sans toucher aux autres journées.
- Périodes de vacances, congés et RTT qui suppriment les alarmes de travail concernées.
- Alarmes personnelles récurrentes par jours, dont **semaines ISO paires/impaires**.
- Report ponctuel de la prochaine occurrence (+10, +30, +60, +120, +180 minutes) et saut de la prochaine occurrence.
- **Réveil à volume progressif**, vibration, écran d'alarme, répétition.
- Sieste rapide de 10 à 90 minutes.
- Deux widgets : prochain réveil avec décalage/saut ; raccourcis sieste 20/30 minutes.
- Notification de rappel vendredi à 18 h si la semaine suivante n'est pas renseignée.
- Export facultatif des congés et postes (120 prochains jours) vers un calendrier Android choisi, y compris Google Agenda si présent.
- Interface française Jetpack Compose / Material 3, couleurs dynamiques Android 12+ et mode sombre.

## Installer et compiler

Requis : Android Studio avec SDK Android 36 et JDK 17, ou Gradle 8.14.3.

1. Ouvrir le dépôt dans Android Studio.
2. Attendre la synchronisation Gradle (Android Gradle Plugin 8.11.1, Kotlin 2.2.20).
3. Brancher un téléphone Android 8+ et lancer l'application ou construire un APK avec la tâche Gradle assembleDebug.
4. Le pipeline **Actions → Android CI** fabrique un APK debug téléchargeable dans ses **Artifacts** lorsque le build réussit.

**Ne pas utiliser comme seul réveil sans une période de tests réels.** Dans les paramètres, autoriser les alarmes et les notifications ; vérifier aussi les paramètres système du constructeur concernant les alarmes.

## Architecture

- Model.kt : état local, sérialisation JSON.
- Engine.kt : calcul des occurrences, semaines ISO et AlarmManager.
- AlarmRuntime.kt : récepteur, redémarrage, notification, sonnerie.
- MainActivity.kt : interface Compose (réveils, planning, congés, siestes et réglages).
- CalendarExport.kt : export contrôlé par l'utilisateur vers CalendarContract.
- Widgets.kt et res/layout : widgets Android.

## Limites connues de la v0.1

- Pas encore de simulation de lever de soleil, contrôle QR code, intégration Home Assistant, export/import de sauvegarde, ni synchronisation bidirectionnelle.
- Les congés sont saisis via dates ISO ; éditeur mensuel enrichi prévu.
- L'export ne récupère pas les modifications faites dans les agendas externes.
- Le mécanisme Android ne garantit aucune sonnerie téléphone éteint ou en cas de restrictions constructeur.
- L'interface et l'exécution Android demandent validation sur appareil Samsung réel.

## Principes de calcul

Les journées sont résolues par : **exception journalière → poste hebdomadaire → repos de weekend**, puis les **congés** neutralisent les alarmes de travail. Le réveil est calculé selon la marge choisie avant le début du poste. Les postes de nuit franchissant minuit sont liés à la date de début du poste. Les alarmes personnelles restent indépendantes.

Les données restent stockées localement (SharedPreferences JSON), sans compte obligatoire.

## Feuille de route

- Tests de fiabilité des alarmes, notifications et autorisations.
- Calendrier enrichi, absences partielles, jours fériés français.
- Alarme de secours, simulation d'aube et sonneries personnalisées.
- Sauvegarde, Home Assistant facultatif, localisation anglaise.
