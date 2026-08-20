with open("app/src/main/java/com/example/data/repository/Repositories.kt", "r") as f:
    content = f.read()

# I will find all `pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existing`
# and replace with `return@withTransaction existing`
content = content.replace(
    'pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existing',
    'return@withTransaction existing'
)

# And then I'll just restore the one that I actually wanted:
# Which is inside `resolvePendingOperationVerifiedSuccess`
# I'll just use a regex or specific replacement

# First, undo the other accidental `return@withTransaction existing...` replacements:
content = content.replace(
    'pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existingByIntent',
    'return@withTransaction existingByIntent'
)
content = content.replace(
    'pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existingByTx',
    'return@withTransaction existingByTx'
)

with open("app/src/main/java/com/example/data/repository/Repositories.kt", "w") as f:
    f.write(content)
