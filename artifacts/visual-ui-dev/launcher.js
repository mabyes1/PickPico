const fs = require('fs');
const path = require('path');
const write = process.stdout.write.bind(process.stdout);
process.stdout.write = function (chunk, ...args) {
  fs.appendFileSync(path.join(__dirname, 'startup.log'), chunk);
  return write(chunk, ...args);
};
require('./server.js');
