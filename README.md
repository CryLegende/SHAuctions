<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHAuctions

*Plugin du serveur SkyHand.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Plugin du serveur SkyHand.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `Vault`

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

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`) :

- `config.yml`
- `menus/auction_bids.yml`
- `menus/confirm_purchase.yml`
- `menus/expired_items.yml`
- `menus/main_auction_house.yml`
- `menus/own_sales.yml`
- `menus/sales_history.yml`
- `messages.yml`

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
