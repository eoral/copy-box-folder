# About
- A simple library for copying a Box folder from one account to another

# Usage
- Open this file: `src/main/resources/box-config-to-access-source-folder.json`
- Paste Box connection settings of source account.
- Open this file: `src/main/resources/box-config-to-access-target-folder.json`
- Paste Box connection settings of target account.
- Open this file: `src/main/resources/app.properties`
- Set `sourceFolderId` and `targetFolderId`.
- Run the application.

# Output
- When the application completes copying, it prints a JSON string like below to the console:
```
[
  {
    "type": "FOLDER",
    "sourceId": "5191539104502",
    "targetId": "9549287658304"
  },
  {
    "type": "FILE",
    "sourceId": "7784280631089",
    "targetId": "6313869044464"
  },
  ...
]
```

# Notes
- The main objective is to copy data between different Box accounts, but technically it is OK to copy data within the same account. In other words, source and target Box accounts can be the same.
- Target folder should already exist, it won't be created by the application.
- Files are matched with their names only. Their content won't be checked by the application.

