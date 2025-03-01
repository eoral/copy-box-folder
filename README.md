# About
- A simple library for copying a Box folder from one account to another

# Usage
- Clone this repo.
- Open this file: `src/main/resources/box-config-to-access-source-folder.json`
- Paste Box connection settings of source account.
- Open this file: `src/main/resources/box-config-to-access-target-folder.json`
- Paste Box connection settings of target account.
- Open this file: `src/main/resources/app.properties`
- Set `sourceFolderId` and `targetFolderId`.
- Build and run the application.
  - `cd copy-box-folder`
  - `mvn clean package`
  - `java -Djava.net.useSystemProxies=true -classpath target\copy-box-folder-0.2.jar;target\libs\* com.eoral.copyboxfolder.App`

# Output
When the application completes copying, you should see a line in the console like this:
`Output file is here: C:\Users\eoral\copy-box-folder-output-20250228-125847.json`

When you open this file, you should see a content similar to this:
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

