<template>
  <div class="row">
    <div class="col">
      <form @submit.prevent="onSubmit">
        <div class="form-group">
          <label for="memberSelect">User</label>
          <select v-model="member.id" class="form-control" id="memberSelect" required>
            <option v-for="user in unassignedUsers" :value="user.id">{{ user.label }}</option>
          </select>
        </div>
        <div class="form-group">
          <label for="roleSelect">Role</label>
          <select v-model="member.role" class="form-control" id="roleSelect" required>
            <option v-for="role in context.children" :value="role.id">{{ role.label }}</option>
          </select>
        </div>
        <div class="form-group">
          <label for="fromDate">From</label>
          <input v-model="member.from" type="date" class="form-control" id="fromDate" required>
        </div>
        <div class="form-group" :class="{'has-danger': untilDateBeforeFromDateError}">
          <label for="untilDate">Until</label>
          <input v-model="member.until" type="date" class="form-control" id="untilDate">
          <div v-if="untilDateBeforeFromDateError" class="form-control-feedback">
            Until date must be after the from date
          </div>
        </div>
        <button type="submit" class="btn btn-success">Save</button>
      </form>
    </div>
  </div>
</template>

<script>
  import { CREATE_MEMBER } from '../store/actions'
  import { mapGetters, mapState } from 'vuex'
  import moment from 'moment'

  export default {
    name: 'member-create',
    data: function () {
      return {
        member: {
          type: 'user',
          id: null,
          role: null,
          from: moment().format('YYYY-MM-DD'),
          until: null
        },
        untilDateBeforeFromDateError: false
      }
    },
    computed: {
      ...mapState(['users']),
      ...mapGetters(['members', 'unassignedUsers', 'context'])
    },
    methods: {
      onSubmit: function () {
        if (this.member.until && moment(this.member.until, 'YYYY-MM-DD').isBefore(moment(this.member.from, 'YYYY-MM-DD'))) {
          this.untilDateBeforeFromDateError = true
        } else {
          this.$store.dispatch(CREATE_MEMBER, this.member).then(() => this.$router.go(-1))
        }
      }
    }
  }
</script>

<style scoped>
  button {
    cursor: pointer;
  }
</style>
