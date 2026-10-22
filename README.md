<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHAuctions

*Hôtel des ventes (/hdv) : ventes directes et enchères, invendus, historique et annulation par le staff.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Hôtel des ventes (/hdv) : ventes directes et enchères, invendus, historique et annulation par le staff.

- **Saisie par dialogue natif** : sur un client 1.21.6 ou plus, miser sur une enchère (clic gauche dans la page Enchères) ou donner la raison d'une annulation staff (clic droit) ouvre un dialogue avec un champ de saisie et deux boutons, **Miser / Valider** et **Annuler**, à la place de « saisissez dans le chat, tapez annuler ». Échap annule proprement. Le dialogue rappelle l'objet, la meilleure offre, la mise minimum et le temps restant.
- **Mêmes règles que le chat** : le montant est lu comme au chat (virgule décimale, espaces ignorés, strictement positif) puis transmis à la même méthode de mise, qui revérifie tout côté serveur : enchère encore ouverte, vendeur, meilleur enchérisseur, minimum, incrément, solde, concurrence. Les messages d'erreur sont ceux du chat. Aucun plafond n'est imposé aux gros montants (champ texte, pas de curseur).
- **Saisie à usage unique** : chaque dialogue porte un jeton aléatoire ; un clic n'est pris en compte que si le jeton, le type de saisie et le joueur correspondent, avant expiration (`dialogues.expiration-secondes`). La saisie est annulée si le joueur se déconnecte ou si l'enchère disparaît (annulée, expirée, remportée).
- **Repli sur le chat** : les clients plus anciens (ViaVersion donne leur version), `dialogues.actif: false` ou une API de dialogues absente gardent exactement la saisie par chat d'avant.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `Vault`, `ViaVersion` (version du client pour choisir dialogue ou chat ; sans lui tous les clients sont considérés compatibles)

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/hdv` | `/ah`, `/auctionhouse` | Ouvre l'hôtel des ventes ou gère les enchères. | — |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shauctions.use` | Permet d'utiliser les fonctionnalités de base de l'HDV (ouvrir, voir). | `true` |
| `shauctions.sell` | Permet de mettre des items en vente. | `true` |
| `shauctions.buy` | Permet d'acheter des items. | `true` |
| `shauctions.bid` | Permet de mettre des items aux enchères et de miser. | `true` |
| `shauctions.menu.bids` | Permet d'ouvrir la page des Enchères. | `true` |
| `shauctions.menu.ownsales` | Affiche le bouton "Vos objets en vente" dans les menus. | `true` |
| `shauctions.menu.expired` | Affiche le bouton "Vos invendus" dans les menus. | `true` |
| `shauctions.menu.history` | Affiche le bouton "Historique des ventes" dans les menus. | `true` |
| `shauctions.history.self` | Permet de voir son propre historique de ventes. | `true` |
| `shauctions.history.global` | Permet de voir l'historique global des ventes. | `op` |
| `shauctions.expired.claim` | Permet de réclamer ses items expirés. | `true` |
| `shauctions.cancel.self` | Permet d'annuler ses propres ventes. | `true` |
| `shauctions.cancel.others` | Permet d'annuler les ventes des autres joueurs (admin). | `op` |
| `shauctions.reload` | Permet de recharger la configuration du plugin. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHAuctions/
├── <a href="src/main/resources/config.yml">config.yml</a>        dialogues.actif, dialogues.min-protocol (771 = 1.21.6), dialogues.expiration-secondes
├── <a href="src/main/resources/messages.yml">messages.yml</a>      section dialogues (titres, champs, boutons, erreurs) et messages dialog_*
└── menus/
    ├── <a href="src/main/resources/menus/auction_bids.yml">auction_bids.yml</a>
    ├── <a href="src/main/resources/menus/confirm_purchase.yml">confirm_purchase.yml</a>
    ├── <a href="src/main/resources/menus/expired_items.yml">expired_items.yml</a>
    ├── <a href="src/main/resources/menus/main_auction_house.yml">main_auction_house.yml</a>
    ├── <a href="src/main/resources/menus/own_sales.yml">own_sales.yml</a>
    └── <a href="src/main/resources/menus/sales_history.yml">sales_history.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
